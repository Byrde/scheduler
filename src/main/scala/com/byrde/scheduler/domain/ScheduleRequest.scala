package com.byrde.scheduler.domain

/**
 * Aggregate root representing a validated request to schedule a message for future delivery.
 * 
 * This is the entry point to the scheduling domain, enforcing invariants such as:
 * - Schedule type must be valid (one-time or recurring)
 * - Target topic must be valid
 * - Payload must be non-empty
 */
final case class ScheduleRequest(
  scheduleType: ScheduleType,
  targetTopic: TargetTopic,
  payload: MessagePayload,
  taskName: Option[String] = None  // Optional custom task name for recurring tasks
) {
  /**
   * Converts this schedule request into a scheduled message with a unique task ID.
   */
  def toScheduledMessage(taskId: TaskId): ScheduledMessage = {
    val executionTime = scheduleType match {
      case ScheduleType.OneTime(execTime) => execTime
      case ScheduleType.Cron(_, Some(execTime)) => execTime
      case ScheduleType.FixedDelay(_, Some(execTime)) => execTime
      case ScheduleType.Daily(_, _, Some(execTime)) => execTime
      case _ => ExecutionTime(java.time.Instant.now().plusSeconds(60)) // Default to 1 minute from now for recurring
    }
    
    ScheduledMessage(
      taskId = taskId,
      executionTime = executionTime,
      targetTopic = targetTopic,
      payload = payload,
      status = TaskStatus.Scheduled,
      scheduleType = scheduleType
    )
  }
  
  /**
   * Checks if this is a one-time task
   */
  def isOneTime: Boolean = scheduleType.isInstanceOf[ScheduleType.OneTime]
  
  /**
   * Checks if this is a recurring task
   */
  def isRecurring: Boolean = !isOneTime
}

object ScheduleRequest {
  /**
   * Creates a one-time schedule request from raw components with validation.
   */
  def create(
    executionTimeMillis: Long,
    topicName: String,
    payloadData: Array[Byte],
    payloadAttributes: Map[String, String] = Map.empty
  ): Either[String, ScheduleRequest] = {
    for {
      execTime <- ExecutionTime.fromEpochMillis(executionTimeMillis)
      topic <- validateTopic(topicName)
      payload <- validatePayload(payloadData, payloadAttributes)
    } yield ScheduleRequest(ScheduleType.OneTime(execTime), topic, payload)
  }
  
  /**
   * Creates a schedule request with a specific schedule type
   */
  def createWithSchedule(
    scheduleType: ScheduleType,
    topicName: String,
    payloadData: Array[Byte],
    payloadAttributes: Map[String, String] = Map.empty,
    taskName: Option[String] = None
  ): Either[String, ScheduleRequest] = {
    for {
      topic <- validateTopic(topicName)
      payload <- validatePayload(payloadData, payloadAttributes)
    } yield ScheduleRequest(scheduleType, topic, payload, taskName)
  }
  
  private def validateTopic(name: String): Either[String, TargetTopic] = {
    try {
      Right(TargetTopic(name))
    } catch {
      case e: IllegalArgumentException => Left(e.getMessage)
    }
  }
  
  private def validatePayload(
    data: Array[Byte],
    attributes: Map[String, String]
  ): Either[String, MessagePayload] = {
    try {
      Right(MessagePayload(data, attributes))
    } catch {
      case e: IllegalArgumentException => Left(e.getMessage)
    }
  }
}

