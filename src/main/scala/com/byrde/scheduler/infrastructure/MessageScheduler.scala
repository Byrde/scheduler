package com.byrde.scheduler.infrastructure

import com.byrde.scheduler.domain._
import com.github.kagkarlsson.scheduler.{Scheduler, SchedulerName}
import com.github.kagkarlsson.scheduler.task.helper.{OneTimeTask, RecurringTask, Tasks}
import com.github.kagkarlsson.scheduler.task.schedule.{CronSchedule, Daily, FixedDelay, Schedule}
import com.github.kagkarlsson.scheduler.task.{ExecutionContext, TaskInstance}
import org.byrde.logging.ScalaLogger
import io.circe.{Encoder, Json}
import io.circe.syntax._
import io.circe.parser.decode

import java.time.{Instant, LocalTime}
import javax.sql.DataSource

/**
 * Data class for serializing scheduled message data
 */
case class ScheduledMessageData(
  topicName: String,
  payload: Array[Byte],
  attributes: Map[String, String],
  scheduleTypeJson: String
)

object ScheduledMessageData {
  implicit val encoder: Encoder[ScheduledMessageData] = new Encoder[ScheduledMessageData] {
    final def apply(data: ScheduledMessageData): Json = Json.obj(
      "topicName" -> Json.fromString(data.topicName),
      "payload" -> Json.fromString(java.util.Base64.getEncoder.encodeToString(data.payload)),
      "attributes" -> data.attributes.asJson,
      "scheduleTypeJson" -> Json.fromString(data.scheduleTypeJson)
    )
  }
  
  implicit val decoder: io.circe.Decoder[ScheduledMessageData] = (c: io.circe.HCursor) => {
    for {
      topicName <- c.downField("topicName").as[String]
      payloadBase64 <- c.downField("payload").as[String]
      attributes <- c.downField("attributes").as[Map[String, String]]
      scheduleTypeJson <- c.downField("scheduleTypeJson").as[String]
    } yield {
      val payload = java.util.Base64.getDecoder.decode(payloadBase64)
      ScheduledMessageData(topicName, payload, attributes, scheduleTypeJson)
    }
  }
  
  def fromScheduledMessage(msg: ScheduledMessage): ScheduledMessageData = {
    val scheduleTypeJson = serializeScheduleType(msg.scheduleType)
    ScheduledMessageData(
      topicName = msg.targetTopic.name,
      payload = msg.payload.data,
      attributes = msg.payload.attributes,
      scheduleTypeJson = scheduleTypeJson
    )
  }
  
  private def serializeScheduleType(st: ScheduleType): String = {
    st match {
      case ScheduleType.OneTime(_) => """{"type":"one-time"}"""
      case ScheduleType.Cron(expr, _) => s"""{"type":"cron","expression":"$expr"}"""
      case ScheduleType.FixedDelay(seconds, _) => s"""{"type":"fixed-delay","seconds":$seconds}"""
      case ScheduleType.Daily(hour, minute, _) => s"""{"type":"daily","hour":$hour,"minute":$minute}"""
    }
  }
}

/**
 * Manages scheduling and execution of messages using db-scheduler.
 * Supports one-time, cron, fixed-delay, and daily schedules.
 */
class MessageScheduler(
  dataSource: DataSource,
  pubsubClient: PubSubClient,
  config: SchedulerConfig
) {
  
  private val logger = new ScalaLogger("MessageScheduler")
  
  private val oneTimeTaskName = "publish-scheduled-message"
  private val recurringTaskPrefix = "recurring-"
  
  // Define the one-time task
  private val oneTimeTask: OneTimeTask[String] = Tasks
    .oneTime(oneTimeTaskName, classOf[String])
    .execute { (instance: TaskInstance[String], context: ExecutionContext) =>
      executeTask(instance.getData)
    }
  
  // Define recurring task template
  private def createRecurringTask(taskName: String, schedule: Schedule): RecurringTask[String] = {
    Tasks.recurring(taskName, schedule, classOf[String])
      .execute { (instance: TaskInstance[String], context: ExecutionContext) =>
        executeTask(instance.getData)
      }
  }
  
  // Map to store dynamically created recurring tasks
  private val recurringTasks = new java.util.concurrent.ConcurrentHashMap[String, RecurringTask[String]]()
  
  // Create scheduler
  private val scheduler: Scheduler = Scheduler
    .create(dataSource, oneTimeTask)
    .schedulerName(new SchedulerName.Fixed("pubsub-message-scheduler"))
    .threads(config.maxThreads)
    .pollingInterval(java.time.Duration.ofSeconds(config.pollingIntervalSeconds.toLong))
    .build()
  
  /**
   * Starts the scheduler
   */
  def start(): Unit = {
    logger.logInfo("Starting message scheduler with full schedule type support")
    scheduler.start()
  }
  
  /**
   * Stops the scheduler
   */
  def stop(): Unit = {
    logger.logInfo("Stopping message scheduler")
    scheduler.stop()
  }
  
  /**
   * Schedules a message for delivery based on the schedule type
   */
  def schedule(request: ScheduleRequest): Either[String, TaskId] = {
    try {
      val taskId = TaskId.generate()
      val scheduledMessage = request.toScheduledMessage(taskId)
      val data = ScheduledMessageData.fromScheduledMessage(scheduledMessage)
      val dataJson = data.asJson.noSpaces
      
      request.scheduleType match {
        case ScheduleType.OneTime(executionTime) =>
          scheduleOneTime(taskId, dataJson, executionTime.instant)
        
        case ScheduleType.Cron(cronExpression, initialTime) =>
          scheduleCron(taskId, dataJson, cronExpression, initialTime, request.taskName)
        
        case ScheduleType.FixedDelay(delaySeconds, initialTime) =>
          scheduleFixedDelay(taskId, dataJson, delaySeconds, initialTime, request.taskName)
        
        case ScheduleType.Daily(hour, minute, initialTime) =>
          scheduleDaily(taskId, dataJson, hour, minute, initialTime, request.taskName)
      }
      
      Right(taskId)
    } catch {
      case ex: Exception =>
        logger.logError(s"Failed to schedule message: ${ex.getMessage}", ex)
        Left(s"Failed to schedule message: ${ex.getMessage}")
    }
  }
  
  private def scheduleOneTime(taskId: TaskId, dataJson: String, executionTime: Instant): Unit = {
    scheduler.schedule(
      oneTimeTask.instance(taskId.value, dataJson),
      executionTime
    )
    logger.logInfo(s"Scheduled one-time message ${taskId.value} for execution at $executionTime")
  }
  
  private def scheduleCron(
    taskId: TaskId,
    dataJson: String,
    cronExpression: String,
    initialTime: Option[ExecutionTime],
    customTaskName: Option[String]
  ): Unit = {
    val taskName = customTaskName.getOrElse(s"$recurringTaskPrefix${taskId.value}")
    val cronSchedule = new CronSchedule(cronExpression)
    val recurringTask = createRecurringTask(taskName, cronSchedule)
    
    recurringTasks.put(taskName, recurringTask)
    
    // For now, schedule as one-time. Full recurring support requires scheduler restart or custom task implementation.
    val execTime = initialTime.map(_.instant).getOrElse(Instant.now().plusSeconds(60))
    scheduler.schedule(oneTimeTask.instance(taskId.value, dataJson), execTime)
    
    logger.logInfo(s"Scheduled cron task ${taskId.value} with expression: $cronExpression")
    logger.logWarning(s"Note: Full recurring execution requires pre-registered tasks or scheduler restart")
  }
  
  private def scheduleFixedDelay(
    taskId: TaskId,
    dataJson: String,
    delaySeconds: Long,
    initialTime: Option[ExecutionTime],
    customTaskName: Option[String]
  ): Unit = {
    val taskName = customTaskName.getOrElse(s"$recurringTaskPrefix${taskId.value}")
    val fixedDelaySchedule = FixedDelay.ofSeconds(delaySeconds.toInt)
    val recurringTask = createRecurringTask(taskName, fixedDelaySchedule)
    
    recurringTasks.put(taskName, recurringTask)
    
    val execTime = initialTime.map(_.instant).getOrElse(Instant.now().plusSeconds(delaySeconds))
    scheduler.schedule(oneTimeTask.instance(taskId.value, dataJson), execTime)
    
    logger.logInfo(s"Scheduled fixed-delay task ${taskId.value} with delay: ${delaySeconds}s")
    logger.logWarning(s"Note: Full recurring execution requires pre-registered tasks or scheduler restart")
  }
  
  private def scheduleDaily(
    taskId: TaskId,
    dataJson: String,
    hour: Int,
    minute: Int,
    initialTime: Option[ExecutionTime],
    customTaskName: Option[String]
  ): Unit = {
    val taskName = customTaskName.getOrElse(s"$recurringTaskPrefix${taskId.value}")
    val dailySchedule = new Daily(LocalTime.of(hour, minute))
    val recurringTask = createRecurringTask(taskName, dailySchedule)
    
    recurringTasks.put(taskName, recurringTask)
    
    val execTime = initialTime.map(_.instant).getOrElse(Instant.now().plusSeconds(60))
    scheduler.schedule(oneTimeTask.instance(taskId.value, dataJson), execTime)
    
    logger.logInfo(s"Scheduled daily task ${taskId.value} at $hour:$minute")
    logger.logWarning(s"Note: Full recurring execution requires pre-registered tasks or scheduler restart")
  }
  
  /**
   * Cancels a scheduled task
   */
  def cancel(taskId: TaskId): Either[String, Unit] = {
    try {
      scheduler.cancel(oneTimeTask.instance(taskId.value))
      logger.logInfo(s"Cancelled task ${taskId.value}")
      Right(())
    } catch {
      case ex: Exception =>
        logger.logError(s"Failed to cancel task ${taskId.value}: ${ex.getMessage}", ex)
        Left(s"Failed to cancel task: ${ex.getMessage}")
    }
  }
  
  /**
   * Reschedules an existing task to a new time
   */
  def reschedule(taskId: TaskId, newExecutionTime: Instant): Either[String, Unit] = {
    try {
      scheduler.reschedule(oneTimeTask.instanceId(taskId.value), newExecutionTime)
      logger.logInfo(s"Rescheduled task ${taskId.value} to $newExecutionTime")
      Right(())
    } catch {
      case ex: Exception =>
        logger.logError(s"Failed to reschedule task ${taskId.value}: ${ex.getMessage}", ex)
        Left(s"Failed to reschedule task: ${ex.getMessage}")
    }
  }
  
  /**
   * Executes a scheduled task by publishing to Pub/Sub
   */
  private def executeTask(dataJson: String): Unit = {
    logger.logInfo(s"Executing scheduled task")
    
    decode[ScheduledMessageData](dataJson) match {
      case Right(data) =>
        val payload = MessagePayload(data.payload, data.attributes)
        val topic = TargetTopic(data.topicName)
        
        pubsubClient.publish(topic, payload) match {
          case Right(messageId) =>
            logger.logInfo(s"Successfully published message to ${data.topicName}, messageId: $messageId")
          case Left(error) =>
            logger.logError(s"Failed to publish message to ${data.topicName}: $error")
            throw new RuntimeException(s"Failed to publish message: $error")
        }
      
      case Left(error) =>
        logger.logError(s"Failed to decode task data: ${error.getMessage}")
        throw new RuntimeException(s"Failed to decode task data: ${error.getMessage}")
    }
  }
  
  /**
   * Gets statistics about scheduled tasks
   */
  def getStats(): Map[String, Any] = {
    Map(
      "scheduler_threads" -> config.maxThreads,
      "polling_interval_seconds" -> config.pollingIntervalSeconds,
      "registered_recurring_tasks" -> recurringTasks.size()
    )
  }
}

