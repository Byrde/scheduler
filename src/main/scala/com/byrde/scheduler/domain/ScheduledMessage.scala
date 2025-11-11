package com.byrde.scheduler.domain

/**
 * Aggregate root representing a persisted scheduled message in the database.
 * 
 * Enforces lifecycle rules:
 * - A task can be one-time or recurring
 * - Execution results must be recorded
 * - Failed executions can be retried
 * 
 * This aggregate bridges the domain model to db-scheduler's task representation.
 */
final case class ScheduledMessage(
  taskId: TaskId,
  executionTime: ExecutionTime,
  targetTopic: TargetTopic,
  payload: MessagePayload,
  status: TaskStatus,
  scheduleType: ScheduleType,
  failureCount: Int = 0,
  lastError: Option[String] = None
) {
  /**
   * Marks this message as currently executing.
   */
  def markExecuting(): ScheduledMessage = {
    copy(status = TaskStatus.Executing)
  }
  
  /**
   * Marks this message as successfully completed.
   */
  def markCompleted(): ScheduledMessage = {
    copy(status = TaskStatus.Completed)
  }
  
  /**
   * Marks this message as failed with an error message.
   */
  def markFailed(error: String): ScheduledMessage = {
    copy(
      status = TaskStatus.Failed,
      failureCount = failureCount + 1,
      lastError = Some(error)
    )
  }
  
  /**
   * Determines if this message can be retried based on failure count.
   */
  def canRetry(maxRetries: Int): Boolean = {
    status == TaskStatus.Failed && failureCount < maxRetries
  }
  
  /**
   * Checks if this message is ready for execution.
   */
  def isReady: Boolean = {
    status == TaskStatus.Scheduled && executionTime.isReady
  }
}

