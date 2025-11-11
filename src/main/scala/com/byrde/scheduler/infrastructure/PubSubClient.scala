package com.byrde.scheduler.infrastructure

import com.byrde.scheduler.domain.{MessagePayload, TargetTopic}
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.{GoogleCredentials, ServiceAccountCredentials}
import com.google.cloud.pubsub.v1.{Publisher, TopicAdminClient, TopicAdminSettings}
import com.google.protobuf.ByteString
import com.google.pubsub.v1.{PubsubMessage, TopicName}
import org.byrde.logging.ScalaLogger

import java.io.FileInputStream
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/**
 * Client for publishing messages to Google Cloud Pub/Sub
 */
class PubSubClient(config: PubSubConfig) {
  
  private val logger = new ScalaLogger("PubSubClient")
  
  private val credentials: GoogleCredentials = loadCredentials()
  
  // Cache of publishers by topic name
  private val publishers = scala.collection.mutable.Map[String, Publisher]()
  
  private def loadCredentials(): GoogleCredentials = {
    config.credentialsPath match {
      case Some(path) =>
        logger.logInfo(s"Loading Pub/Sub credentials from: $path")
        ServiceAccountCredentials.fromStream(new FileInputStream(path))
      case None =>
        logger.logInfo("Using default application credentials")
        GoogleCredentials.getApplicationDefault
    }
  }
  
  /**
   * Publishes a message to the specified topic
   */
  def publish(topic: TargetTopic, payload: MessagePayload): Either[String, String] = {
    Try {
      val topicName = topic.toFullName(config.projectId)
      val publisher = getOrCreatePublisher(topicName)
      
      val pubsubMessage = PubsubMessage.newBuilder()
        .setData(ByteString.copyFrom(payload.data))
        .putAllAttributes(payload.attributes.asJava)
        .build()
      
      val future = publisher.publish(pubsubMessage)
      val messageId = future.get()
      
      logger.logDebug(s"Published message to $topicName with ID: $messageId")
      messageId
    } match {
      case Success(messageId) => Right(messageId)
      case Failure(ex) =>
        logger.logError(s"Failed to publish message to ${topic.name}: ${ex.getMessage}", ex)
        Left(ex.getMessage)
    }
  }
  
  /**
   * Gets or creates a publisher for the given topic
   */
  private def getOrCreatePublisher(topicName: String): Publisher = {
    publishers.getOrElseUpdate(topicName, {
      logger.logInfo(s"Creating publisher for topic: $topicName")
      val credentialsProvider = FixedCredentialsProvider.create(credentials)
      
      Publisher.newBuilder(topicName)
        .setCredentialsProvider(credentialsProvider)
        .build()
    })
  }
  
  /**
   * Verifies that a topic exists (for validation purposes)
   */
  def topicExists(topic: TargetTopic): Boolean = {
    Try {
      val credentialsProvider = FixedCredentialsProvider.create(credentials)
      val adminSettings = TopicAdminSettings.newBuilder()
        .setCredentialsProvider(credentialsProvider)
        .build()
      
      val adminClient = TopicAdminClient.create(adminSettings)
      try {
        val topicName = TopicName.parse(topic.toFullName(config.projectId))
        adminClient.getTopic(topicName)
        true
      } finally {
        adminClient.close()
      }
    } match {
      case Success(_) => true
      case Failure(ex) =>
        logger.logWarning(s"Topic ${topic.name} does not exist or cannot be accessed: ${ex.getMessage}")
        false
    }
  }
  
  /**
   * Tests Pub/Sub connectivity by listing topics (health check)
   */
  def testConnection(): Either[String, Unit] = {
    Try {
      val credentialsProvider = FixedCredentialsProvider.create(credentials)
      val adminSettings = TopicAdminSettings.newBuilder()
        .setCredentialsProvider(credentialsProvider)
        .build()
      
      val adminClient = TopicAdminClient.create(adminSettings)
      try {
        // List topics to verify we can connect and authenticate
        val projectName = s"projects/${config.projectId}"
        val topics = adminClient.listTopics(projectName)
        // Just verify we can iterate (lazy evaluation)
        topics.iterateAll().iterator().hasNext
        logger.logDebug("Pub/Sub connection test successful")
        ()
      } finally {
        adminClient.close()
      }
    } match {
      case Success(_) => Right(())
      case Failure(ex) =>
        logger.logError(s"Pub/Sub connection test failed: ${ex.getMessage}", ex)
        Left(s"Pub/Sub connection failed: ${ex.getMessage}")
    }
  }
  
  /**
   * Shuts down all publishers
   */
  def shutdown(): Unit = {
    logger.logInfo("Shutting down Pub/Sub publishers")
    publishers.values.foreach { publisher =>
      try {
        publisher.shutdown()
      } catch {
        case ex: Exception => logger.logError(s"Error shutting down publisher: ${ex.getMessage}", ex)
      }
    }
    publishers.clear()
  }
}

