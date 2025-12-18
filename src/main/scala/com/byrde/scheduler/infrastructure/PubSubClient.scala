package com.byrde.scheduler.infrastructure

import com.byrde.scheduler.domain.{MessagePayload, TargetTopic}
import org.byrde.logging.{Logger, ScalaLogger}
import org.byrde.pubsub._
import org.byrde.pubsub.conf.{PubSubConfig => CommonsPubSubConfig}
import org.byrde.pubsub.google.GooglePubSubPublisher

import io.circe.Encoder

import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Client for publishing messages to Google Cloud Pub/Sub.
 * Wraps the Byrde commons GooglePubSubPublisher.
 */
class PubSubClient(config: CommonsPubSubConfig)(implicit ec: ExecutionContextExecutor) {
  
  private val logger: Logger = new ScalaLogger("PubSubClient")
  
  private val publisher = new GooglePubSubPublisher(config, logger)
  
  // Encoder for raw byte payload - encodes as base64 string
  private implicit val byteArrayEncoder: Encoder[Array[Byte]] = 
    Encoder.encodeString.contramap(bytes => java.util.Base64.getEncoder.encodeToString(bytes))
  
  /**
   * Publishes a message to the specified topic
   */
  def publish(topic: TargetTopic, payload: MessagePayload): Either[String, String] = {
    Try {
      val envelope = Envelope(
        topic = topic.name,
        msg = payload.data,
        metadata = payload.attributes
      )
      
      val future = publisher.publish(envelope)
      Await.result(future, 30.seconds)
    } match {
      case Success(Right(_)) => 
        logger.logDebug(s"Published message to ${topic.name}")
        Right(s"published-to-${topic.name}")
      case Success(Left(error)) =>
        val errorMsg = error match {
          case PubSubError.PublishError(msg, _) => msg
          case other => other.toString
        }
        logger.logError(s"Failed to publish message to ${topic.name}: $errorMsg")
        Left(errorMsg)
      case Failure(ex) =>
        logger.logError(s"Failed to publish message to ${topic.name}: ${ex.getMessage}", ex)
        Left(ex.getMessage)
    }
  }
  
  /**
   * Tests Pub/Sub connectivity by attempting a simple operation.
   * Note: The commons library auto-creates topics on publish, so we verify credentials are valid.
   */
  def testConnection(): Either[String, Unit] = {
    Try {
      // Verify credentials are valid by checking they can be refreshed
      config.credentials.refresh()
      logger.logDebug("Pub/Sub credentials validated successfully")
      Right(())
    } match {
      case Success(result) => result
      case Failure(ex) =>
        logger.logError(s"Pub/Sub credential validation failed: ${ex.getMessage}", ex)
        Left(s"Pub/Sub connection failed: ${ex.getMessage}")
    }
  }
  
  /**
   * Shuts down the publisher
   */
  def shutdown(): Unit = {
    logger.logInfo("Shutting down Pub/Sub publisher")
    publisher.close()
  }
}
