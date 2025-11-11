package com.byrde.scheduler

import com.byrde.scheduler.api.{PubSubSubscriber, ScheduleRequestParser}
import com.byrde.scheduler.domain.ScheduleRequest
import com.byrde.scheduler.infrastructure._
import org.byrde.logging.ScalaLogger

import scala.io.StdIn
import scala.util.{Failure, Success, Try}

/**
 * Main application with CLI for testing and service mode
 */
object Main {
  
  private val logger = new ScalaLogger("Main")
  
  private var components: Option[AppComponents] = None
  
  case class AppComponents(
    config: AppConfig,
    dbManager: DatabaseManager,
    pubsubClient: PubSubClient,
    scheduler: MessageScheduler,
    subscriber: PubSubSubscriber,
    healthServer: HealthCheckServer
  )
  
  def main(args: Array[String]): Unit = {
    if (args.isEmpty) {
      printUsage()
      System.exit(1)
    }
    
    args(0).toLowerCase match {
      case "start" => startService()
      case "schedule" => scheduleMessage(args.drop(1))
      case "parse" => parseMessage(args.drop(1))
      case "help" => printUsage()
      case _ =>
        println(s"Unknown command: ${args(0)}")
        printUsage()
        System.exit(1)
    }
  }
  
  private def printUsage(): Unit = {
    println(
      """
        |Pub/Sub Message Scheduler CLI
        |
        |Usage: java -jar pubsub-message-scheduler.jar <command> [options]
        |
        |Commands:
        |  start           Start the scheduler service (runs indefinitely)
        |  schedule        Schedule a message (interactive)
        |  parse           Parse and validate a schedule request JSON
        |  help            Show this help message
        |
        |Environment Variables:
        |  DATABASE_URL              JDBC connection string (required)
        |  PUBSUB_PROJECT_ID         GCP project ID (required)
        |  PUBSUB_SUBSCRIPTION       Subscription name for inbound messages (required)
        |  PUBSUB_CREDENTIALS_PATH   Path to service account JSON (optional)
        |  MAX_THREADS               Scheduler thread pool size (default: 10)
        |  POLLING_INTERVAL_SECONDS  Scheduler polling interval (default: 10)
        |
        |Examples:
        |  # Start service
        |  java -jar pubsub-message-scheduler.jar start
        |
        |  # Schedule a message interactively
        |  java -jar pubsub-message-scheduler.jar schedule
        |""".stripMargin
    )
  }
  
  /**
   * Starts the full service
   */
  private def startService(): Unit = {
    logger.logInfo("Starting Pub/Sub Message Scheduler")
    
    try {
      val config = Config.load()
      Config.validate(config) match {
        case Left(error) =>
          logger.logError(s"Configuration error: $error")
          System.exit(1)
        case Right(_) => ()
      }
      
      // Initialize components
      val dbManager = new DatabaseManager(config.database)
      val pubsubClient = new PubSubClient(config.pubsub)
      val scheduler = new MessageScheduler(dbManager.getDataSource, pubsubClient, config.scheduler)
      val subscriber = new PubSubSubscriber(config.pubsub, scheduler)
      val healthServer = new HealthCheckServer(8080, dbManager, pubsubClient)
      
      components = Some(AppComponents(config, dbManager, pubsubClient, scheduler, subscriber, healthServer))
      
      // Test database connection
      dbManager.testConnection() match {
        case Left(error) =>
          logger.logError(s"Database connection failed: $error")
          System.exit(1)
        case Right(_) =>
          logger.logInfo("Database connection successful")
      }
      
      // Start components
      healthServer.start()
      scheduler.start()
      subscriber.start()
      
      logger.logInfo("Service started successfully")
      logger.logInfo(s"Listening to subscription: ${config.pubsub.subscriptionName}")
      logger.logInfo("Health check available at http://localhost:8080/health")
      
      // Add shutdown hook
      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        logger.logInfo("Shutting down...")
        shutdown()
      }))
      
      // Keep running
      while (true) {
        Thread.sleep(10000)
        
        // Log stats periodically
        val subStats = subscriber.getStats()
        logger.logInfo(s"Stats - Received: ${subStats("messages_received")}, " +
          s"Processed: ${subStats("messages_processed")}, " +
          s"Failed: ${subStats("messages_failed")}")
      }
      
    } catch {
      case ex: Exception =>
        logger.logError(s"Failed to start service: ${ex.getMessage}", ex)
        shutdown()
        System.exit(1)
    }
  }
  
  /**
   * Interactive message scheduling
   */
  private def scheduleMessage(args: Array[String]): Unit = {
    println("Interactive Message Scheduler")
    println("=" * 40)
    
    try {
      val config = Config.load()
      val dbManager = new DatabaseManager(config.database)
      val pubsubClient = new PubSubClient(config.pubsub)
      val scheduler = new MessageScheduler(dbManager.getDataSource, pubsubClient, config.scheduler)
      
      scheduler.start()
      
      print("Target topic name: ")
      val topicName = StdIn.readLine().trim
      
      print("Message payload (text): ")
      val payloadText = StdIn.readLine()
      
      print("Delay in seconds: ")
      val delaySeconds = StdIn.readLine().trim.toInt
      
      val executionTime = System.currentTimeMillis() + (delaySeconds * 1000)
      
      val request = ScheduleRequest.create(
        executionTimeMillis = executionTime,
        topicName = topicName,
        payloadData = payloadText.getBytes("UTF-8"),
        payloadAttributes = Map.empty
      )
      
      request match {
        case Right(scheduleRequest) =>
          scheduler.schedule(scheduleRequest) match {
            case Right(taskId) =>
              println(s"✓ Message scheduled successfully!")
              println(s"  Task ID: ${taskId.value}")
              println(s"  Topic: $topicName")
              println(s"  Execution: ${new java.util.Date(executionTime)}")
            case Left(error) =>
              println(s"✗ Failed to schedule message: $error")
          }
        case Left(error) =>
          println(s"✗ Invalid request: $error")
      }
      
      scheduler.stop()
      dbManager.close()
      pubsubClient.shutdown()
      
    } catch {
      case ex: Exception =>
        println(s"✗ Error: ${ex.getMessage}")
        ex.printStackTrace()
        System.exit(1)
    }
  }
  
  /**
   * Parses and validates a schedule request JSON
   */
  private def parseMessage(args: Array[String]): Unit = {
    if (args.isEmpty) {
      println("Provide JSON message as argument or via stdin")
      print("Enter JSON: ")
      val json = StdIn.readLine()
      parseAndValidate(json)
    } else {
      parseAndValidate(args.mkString(" "))
    }
  }
  
  private def parseAndValidate(json: String): Unit = {
    println("Parsing schedule request...")
    
    ScheduleRequestParser.parse(json) match {
      case Right(request) =>
        println("✓ Valid schedule request")
        println(s"  Topic: ${request.targetTopic.name}")
        println(s"  Schedule Type: ${request.scheduleType}")
        println(s"  Payload Size: ${request.payload.data.length} bytes")
        println(s"  Attributes: ${request.payload.attributes.size}")
        System.exit(0)
      
      case Left(error) =>
        println(s"✗ Invalid schedule request: $error")
        System.exit(1)
    }
  }
  
  private def shutdown(): Unit = {
    components.foreach { comp =>
      Try(comp.subscriber.stop())
      Try(comp.scheduler.stop())
      Try(comp.pubsubClient.shutdown())
      Try(comp.dbManager.close())
      Try(comp.healthServer.stop())
    }
    logger.logInfo("Shutdown complete")
  }
  
  private def maskConnectionString(url: String): String = {
    // Mask password in connection string
    url.replaceAll(":[^:@]+@", ":****@")
  }
}

