package com.byrde.scheduler.api

import com.byrde.scheduler.infrastructure.MessageScheduler
import org.byrde.logging.{Logger, ScalaLogger}
import org.byrde.pubsub._
import org.byrde.pubsub.conf.{PubSubConfig => CommonsPubSubConfig}
import org.byrde.pubsub.google.GooglePubSubSubscriber

import io.circe.Decoder

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Subscribes to Pub/Sub messages and processes schedule requests.
 * Wraps the Byrde commons GooglePubSubSubscriber.
 */
class PubSubSubscriber(
  config: CommonsPubSubConfig,
  subscriptionName: String,
  messageScheduler: MessageScheduler
)(implicit ec: ExecutionContextExecutor) {
  
  private val logger: Logger = new ScalaLogger("PubSubSubscriber")
  
  private val subscriber = new GooglePubSubSubscriber(config, logger)
  
  private val messagesReceived = new AtomicLong(0)
  private val messagesProcessed = new AtomicLong(0)
  private val messagesFailed = new AtomicLong(0)
  
  @volatile private var isRunningFlag = false
  
  // Topic name for the subscription (used by commons library to create subscription if needed)
  private val topicName = "schedule-requests"
  
  // Custom decoder that extracts the raw message content from the envelope
  private val rawMessageDecoder: Decoder[String] = Decoder.instance { cursor =>
    // The commons library wraps messages in Envelope format
    // Try to get the 'msg' field, or fall back to treating the whole thing as a string
    cursor.downField("msg").as[String](Decoder.decodeString)
      .orElse(cursor.as[String](Decoder.decodeString))
  }
  
  /**
   * Starts the subscriber
   */
  def start(): Unit = {
    logger.logInfo(s"Starting Pub/Sub subscriber for subscription: $subscriptionName")
    
    val handler: Envelope[String] => Future[Either[Nack.type, Ack.type]] = { envelope =>
      handleMessage(envelope)
    }
    
    val subscribeResult = subscriber.subscribe[String](subscriptionName, topicName)(handler)(rawMessageDecoder)
    
    Try {
      Await.result(subscribeResult, 30.seconds)
    } match {
      case Success(Right(_)) =>
        isRunningFlag = true
        logger.logInfo("Pub/Sub subscriber started successfully")
      case Success(Left(error)) =>
        logger.logError(s"Failed to start subscriber: $error")
        throw new RuntimeException(s"Failed to start subscriber: $error")
      case Failure(ex) =>
        logger.logError(s"Failed to start subscriber: ${ex.getMessage}", ex)
        throw ex
    }
  }
  
  /**
   * Stops the subscriber
   */
  def stop(): Unit = {
    logger.logInfo("Stopping Pub/Sub subscriber")
    isRunningFlag = false
    subscriber.close()
  }
  
  /**
   * Handles an incoming Pub/Sub message
   */
  private def handleMessage(envelope: Envelope[String]): Future[Either[Nack.type, Ack.type]] = {
    messagesReceived.incrementAndGet()
    
    logger.logDebug(s"Received message: ${envelope.id}")
    
    // The envelope.msg contains the schedule request JSON
    val messageData = envelope.msg
    
    ScheduleRequestParser.parse(messageData) match {
      case Right(scheduleRequest) =>
        messageScheduler.schedule(scheduleRequest) match {
          case Right(taskId) =>
            logger.logInfo(s"Successfully scheduled task ${taskId.value} from message ${envelope.id}")
            messagesProcessed.incrementAndGet()
            Future.successful(Right(Ack))
          
          case Left(error) =>
            logger.logError(s"Failed to schedule task from message ${envelope.id}: $error")
            messagesFailed.incrementAndGet()
            Future.successful(Left(Nack))
        }
      
      case Left(error) =>
        logger.logError(s"Failed to parse message ${envelope.id}: $error")
        messagesFailed.incrementAndGet()
        // Ack invalid messages to avoid reprocessing
        Future.successful(Right(Ack))
    }
  }
  
  /**
   * Gets statistics about message processing
   */
  def getStats(): Map[String, Long] = {
    Map(
      "messages_received" -> messagesReceived.get(),
      "messages_processed" -> messagesProcessed.get(),
      "messages_failed" -> messagesFailed.get()
    )
  }
  
  /**
   * Checks if the subscriber is running
   */
  def isRunning: Boolean = isRunningFlag
}
