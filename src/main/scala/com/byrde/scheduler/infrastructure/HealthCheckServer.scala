package com.byrde.scheduler.infrastructure

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.byrde.logging.ScalaLogger

import java.net.InetSocketAddress
import scala.util.{Failure, Success, Try}

/**
 * HTTP server that performs active health checks on downstream services
 */
class HealthCheckServer(
  port: Int = 8080,
  databaseManager: DatabaseManager,
  pubSubClient: PubSubClient
) {
  
  private val logger = new ScalaLogger("HealthCheckServer")
  
  private var server: Option[HttpServer] = None
  
  def start(): Unit = {
    Try {
      val httpServer = HttpServer.create(new InetSocketAddress(port), 0)
      
      httpServer.createContext("/health", new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          val startTime = System.currentTimeMillis()
          
          // Check database
          val dbStartTime = System.currentTimeMillis()
          val dbHealth = databaseManager.testConnection()
          val dbDuration = System.currentTimeMillis() - dbStartTime
          
          // Check Pub/Sub
          val pubsubStartTime = System.currentTimeMillis()
          val pubsubHealth = pubSubClient.testConnection()
          val pubsubDuration = System.currentTimeMillis() - pubsubStartTime
          
          val totalDuration = System.currentTimeMillis() - startTime
          
          // Build response
          val dbStatus = dbHealth match {
            case Right(_) => "healthy"
            case Left(error) => s"unhealthy: $error"
          }
          
          val pubsubStatus = pubsubHealth match {
            case Right(_) => "healthy"
            case Left(error) => s"unhealthy: $error"
          }
          
          val isHealthy = dbHealth.isRight && pubsubHealth.isRight
          val overallStatus = if (isHealthy) "healthy" else "unhealthy"
          
          val response = s"""{
            "status": "$overallStatus",
            "checks": {
              "database": {
                "status": "$dbStatus",
                "responseTimeMs": $dbDuration
              },
              "pubsub": {
                "status": "$pubsubStatus",
                "responseTimeMs": $pubsubDuration
              }
            },
            "totalResponseTimeMs": $totalDuration
          }"""
          
          val statusCode = if (isHealthy) 200 else 503
          
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(statusCode, response.length().toLong)
          val os = exchange.getResponseBody
          os.write(response.getBytes)
          os.close()
        }
      })
      
      httpServer.setExecutor(null) // Use default executor
      httpServer.start()
      
      logger.logInfo(s"Health check server started on port $port")
      httpServer
    } match {
      case Success(httpServer) =>
        server = Some(httpServer)
      case Failure(ex) =>
        logger.logError(s"Failed to start health check server: ${ex.getMessage}", ex)
    }
  }
  
  def stop(): Unit = {
    server.foreach { s =>
      logger.logInfo("Stopping health check server")
      s.stop(0)
    }
    server = None
  }
}

