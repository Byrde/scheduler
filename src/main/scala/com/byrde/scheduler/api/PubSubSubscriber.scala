package com.byrde.scheduler.api

import com.byrde.scheduler.infrastructure.{MessageScheduler, PubSubConfig}
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.{GoogleCredentials, ServiceAccountCredentials}
import com.google.cloud.pubsub.v1.{AckReplyConsumer, MessageReceiver, Subscriber}
import com.google.pubsub.v1.{ProjectSubscriptionName, PubsubMessage}
import com.typesafe.scalalogging.LazyLogging

import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Subscribes to Pub/Sub messages and processes schedule requests
 */
class PubSubSubscriber(
  config: PubSubConfig,
  messageScheduler: MessageScheduler
) extends LazyLogging {
  
  private val credentials: GoogleCredentials = loadCredentials()
  private var subscriber: Option[Subscriber] = None
  
  private val messagesReceived = new AtomicLong(0)
  private val messagesProcessed = new AtomicLong(0)
  private val messagesFailed = new AtomicLong(0)
  
  private def loadCredentials(): GoogleCredentials = {
    config.credentialsPath match {
      case Some(path) =>
        logger.info(s"Loading Pub/Sub credentials from: $path")
        ServiceAccountCredentials.fromStream(new FileInputStream(path))
      case None =>
        logger.info("Using default application credentials")
        GoogleCredentials.getApplicationDefault
    }
  }
  
  /**
   * Starts the subscriber
   */
  def start(): Unit = {
    val subscriptionName = ProjectSubscriptionName.of(config.projectId, config.subscriptionName)
    logger.info(s"Starting Pub/Sub subscriber for: ${subscriptionName.toString}")
    
    val receiver: MessageReceiver = new MessageReceiver {
      override def receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit = {
        handleMessage(message, consumer)
      }
    }
    
    val credentialsProvider = FixedCredentialsProvider.create(credentials)
    
    val sub = Subscriber.newBuilder(subscriptionName, receiver)
      .setCredentialsProvider(credentialsProvider)
      .build()
    
    sub.startAsync().awaitRunning()
    subscriber = Some(sub)
    
    logger.info("Pub/Sub subscriber started successfully")
  }
  
  /**
   * Stops the subscriber
   */
  def stop(): Unit = {
    logger.info("Stopping Pub/Sub subscriber")
    subscriber.foreach { sub =>
      sub.stopAsync().awaitTerminated()
    }
    subscriber = None
  }
  
  /**
   * Handles an incoming Pub/Sub message
   */
  private def handleMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit = {
    messagesReceived.incrementAndGet()
    val messageData = message.getData.toStringUtf8
    
    logger.debug(s"Received message: ${message.getMessageId}")
    
    ScheduleRequestParser.parse(messageData) match {
      case Right(scheduleRequest) =>
        messageScheduler.schedule(scheduleRequest) match {
          case Right(taskId) =>
            logger.info(s"Successfully scheduled task ${taskId.value} from message ${message.getMessageId}")
            messagesProcessed.incrementAndGet()
            consumer.ack()
          
          case Left(error) =>
            logger.error(s"Failed to schedule task from message ${message.getMessageId}: $error")
            messagesFailed.incrementAndGet()
            consumer.nack()
        }
      
      case Left(error) =>
        logger.error(s"Failed to parse message ${message.getMessageId}: $error")
        messagesFailed.incrementAndGet()
        // Ack invalid messages to avoid reprocessing
        consumer.ack()
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
  def isRunning: Boolean = subscriber.exists(_.isRunning)
}

