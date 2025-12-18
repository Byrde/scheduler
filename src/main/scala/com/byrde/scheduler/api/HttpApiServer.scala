package com.byrde.scheduler.api

import com.byrde.scheduler.infrastructure.{ApiConfig, MessageScheduler}
import io.circe.generic.auto._
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import org.byrde.logging.ScalaLogger
import sttp.apispec.openapi.circe.yaml._
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.vertx.VertxFutureServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// API request/response models
case class ScheduleApiRequest(
  schedule: ScheduleConfig,
  targetTopic: String,
  payload: PayloadConfig,
  taskName: Option[String] = None
)

case class ScheduleConfig(
  `type`: String,
  executionTime: Option[Long] = None,
  expression: Option[String] = None,
  delaySeconds: Option[Long] = None,
  hour: Option[Int] = None,
  minute: Option[Int] = None,
  initialExecutionTime: Option[Long] = None
)

case class PayloadConfig(
  data: String,
  attributes: Option[Map[String, String]] = None
)

case class ScheduleApiResponse(
  taskId: String,
  status: String,
  message: String
)

case class ErrorResponse(
  error: String,
  details: Option[String] = None
)

/**
 * HTTP API server with Tapir endpoints and optional Basic Auth
 */
class HttpApiServer(
  config: ApiConfig,
  scheduler: MessageScheduler
)(implicit ec: ExecutionContext) {
  
  private val logger = new ScalaLogger("HttpApiServer")
  
  private var vertx: Option[Vertx] = None
  
  // Basic auth security input (optional based on config)
  private val basicAuthInput: EndpointInput[Option[String]] = 
    header[Option[String]]("Authorization")
  
  // POST /schedule endpoint definition
  private val scheduleEndpoint = 
    endpoint
      .post
      .in("schedule")
      .in(basicAuthInput)
      .in(jsonBody[ScheduleApiRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[ScheduleApiResponse])
      .errorOut(
        oneOf[ErrorResponse](
          oneOfVariant(statusCode(StatusCode.BadRequest).and(jsonBody[ErrorResponse])),
          oneOfVariant(statusCode(StatusCode.Unauthorized).and(jsonBody[ErrorResponse])),
          oneOfVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ErrorResponse]))
        )
      )
      .description("Schedule a message for future delivery")
      .summary("Schedule Message")
  
  // Endpoint logic
  private def handleSchedule(
    authHeader: Option[String],
    request: ScheduleApiRequest
  ): Future[Either[ErrorResponse, ScheduleApiResponse]] = Future {
    // Validate auth if enabled
    val authResult: Either[ErrorResponse, Unit] = if (config.isAuthEnabled) {
      validateBasicAuth(authHeader).left.map { error =>
        logger.logWarning(s"Authentication failed: $error")
        ErrorResponse("Unauthorized", Some(error))
      }
    } else {
      Right(())
    }
    
    authResult.flatMap { _ =>
      // Convert API request to JSON for parser
      val json = convertToParserJson(request)
      
      // Parse and validate
      ScheduleRequestParser.parse(json) match {
        case Right(scheduleRequest) =>
          scheduler.schedule(scheduleRequest) match {
            case Right(taskId) =>
              logger.logInfo(s"Message scheduled via API: taskId=${taskId.value}, topic=${request.targetTopic}")
              Right(ScheduleApiResponse(
                taskId = taskId.value,
                status = "scheduled",
                message = "Message scheduled successfully"
              ))
            case Left(error) =>
              logger.logError(s"Failed to schedule message: $error")
              Left(ErrorResponse("Scheduling failed", Some(error)))
          }

        case Left(error) =>
          logger.logWarning(s"Invalid schedule request: $error")
          Left(ErrorResponse("Validation failed", Some(error)))
      }
    }
  }
  
  private def validateBasicAuth(authHeader: Option[String]): Either[String, Unit] = {
    authHeader match {
      case None => 
        Left("Missing Authorization header")

      case Some(header) if !header.startsWith("Basic ") =>
        Left("Invalid Authorization scheme, expected Basic")

      case Some(header) =>
        Try {
          val encoded = header.stripPrefix("Basic ")
          val decoded = new String(Base64.getDecoder.decode(encoded), "UTF-8")
          val parts = decoded.split(":", 2)
          if (parts.length != 2) {
            Left("Invalid Basic auth format")
          } else {
            val (user, pass) = (parts(0), parts(1))
            if (config.username.contains(user) && config.password.contains(pass)) {
              Right(())
            } else {
              Left("Invalid credentials")
            }
          }
        }.getOrElse(Left("Failed to decode Authorization header"))
    }
  }
  
  private def convertToParserJson(request: ScheduleApiRequest): String = {
    import io.circe.Json
    
    val scheduleJson = Json.obj(
      "type" -> Json.fromString(request.schedule.`type`),
      "executionTime" -> request.schedule.executionTime.map(Json.fromLong).getOrElse(Json.Null),
      "expression" -> request.schedule.expression.map(Json.fromString).getOrElse(Json.Null),
      "delaySeconds" -> request.schedule.delaySeconds.map(Json.fromLong).getOrElse(Json.Null),
      "hour" -> request.schedule.hour.map(Json.fromInt).getOrElse(Json.Null),
      "minute" -> request.schedule.minute.map(Json.fromInt).getOrElse(Json.Null),
      "initialExecutionTime" -> request.schedule.initialExecutionTime.map(Json.fromLong).getOrElse(Json.Null)
    ).dropNullValues
    
    val payloadJson = Json.obj(
      "data" -> Json.fromString(request.payload.data),
      "attributes" -> request.payload.attributes
        .map(attrs => Json.obj(attrs.map { case (k, v) => k -> Json.fromString(v) }.toSeq: _*))
        .getOrElse(Json.obj())
    )
    
    val fullJson = Json.obj(
      "schedule" -> scheduleJson,
      "targetTopic" -> Json.fromString(request.targetTopic),
      "payload" -> payloadJson,
      "taskName" -> request.taskName.map(Json.fromString).getOrElse(Json.Null)
    ).dropNullValues
    
    fullJson.noSpaces
  }
  
  def start(): Unit = {
    val v = Vertx.vertx()
    vertx = Some(v)
    
    val interpreter = VertxFutureServerInterpreter()
    
    // Create server logic
    val serverEndpoint = scheduleEndpoint.serverLogic { case (authHeader, request) =>
      handleSchedule(authHeader, request)
    }
    
    // Generate Swagger docs
    val swaggerEndpoints = SwaggerInterpreter()
      .fromServerEndpoints[Future](List(serverEndpoint), "Scheduler API", "1.0")
    
    val router = Router.router(v)
    
    // Attach endpoints to router
    val allEndpoints = serverEndpoint :: swaggerEndpoints
    allEndpoints.foreach { endpoint =>
      interpreter.route(endpoint).apply(router)
    }
    
    val _ = v.createHttpServer()
      .requestHandler(router)
      .listen(config.port)
      .onSuccess { server =>
        logger.logInfo(s"HTTP API server started on port ${config.port}")
        logger.logInfo(s"Swagger UI available at http://localhost:${config.port}/docs")
        if (config.isAuthEnabled) {
          logger.logInfo("Basic authentication is ENABLED")
        } else {
          logger.logWarning("Basic authentication is DISABLED - API is unprotected")
        }
        ()
      }
      .onFailure { err =>
        logger.logError(s"Failed to start HTTP API server: ${err.getMessage}", err)
        ()
      }
  }
  
  def stop(): Unit = {
    vertx.foreach { v =>
      logger.logInfo("Stopping HTTP API server")
      v.close()
    }
    vertx = None
  }
}

/**
 * Companion object for generating OpenAPI documentation
 */
object HttpApiServer {
  
  private val apiInfo = sttp.apispec.openapi.Info(
    title = "Scheduler API",
    version = "1.0.0",
    description = Some("HTTP API for scheduling messages to Pub/Sub topics")
  )
  
  // Endpoint definitions (duplicated for static access without instance)
  private val scheduleEndpoint = 
    endpoint
      .post
      .in("schedule")
      .in(header[Option[String]]("Authorization").description("Basic auth credentials (optional)"))
      .in(jsonBody[ScheduleApiRequest].description("Schedule request"))
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[ScheduleApiResponse].description("Successful scheduling response"))
      .errorOut(
        oneOf[ErrorResponse](
          oneOfVariant(statusCode(StatusCode.BadRequest).and(jsonBody[ErrorResponse].description("Validation error"))),
          oneOfVariant(statusCode(StatusCode.Unauthorized).and(jsonBody[ErrorResponse].description("Authentication error"))),
          oneOfVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ErrorResponse].description("Internal error")))
        )
      )
      .description("Schedule a message for future delivery to a Pub/Sub topic")
      .summary("Schedule Message")
      .tag("Scheduling")
  
  private val endpoints = List(scheduleEndpoint)
  
  /**
   * Generates OpenAPI specification as YAML string
   */
  def generateOpenApiYaml(): String = {
    val openApi = OpenAPIDocsInterpreter().toOpenAPI(endpoints, apiInfo)
    openApi.toYaml
  }
  
  /**
   * Generates OpenAPI specification as JSON string
   */
  def generateOpenApiJson(): String = {
    import io.circe.syntax._
    import sttp.apispec.openapi.circe._
    val openApi = OpenAPIDocsInterpreter().toOpenAPI(endpoints, apiInfo)
    openApi.asJson.spaces2
  }
}
