package com.byrde.scheduler.domain

/**
 * Represents different types of schedules supported by db-scheduler
 */
sealed trait ScheduleType

object ScheduleType {
  /**
   * One-time execution at a specific time
   */
  case class OneTime(executionTime: ExecutionTime) extends ScheduleType
  
  /**
   * Recurring execution based on a cron expression
   * Example: "0 0 * * *" for daily at midnight
   */
  case class Cron(cronExpression: String, initialExecutionTime: Option[ExecutionTime] = None) extends ScheduleType {
    require(cronExpression.nonEmpty, "Cron expression cannot be empty")
    // Basic validation - db-scheduler will do full validation
    require(cronExpression.split("\\s+").length >= 5, "Cron expression must have at least 5 fields")
  }
  
  /**
   * Recurring execution with a fixed delay between executions
   */
  case class FixedDelay(delaySeconds: Long, initialExecutionTime: Option[ExecutionTime] = None) extends ScheduleType {
    require(delaySeconds > 0, "Delay must be positive")
  }
  
  /**
   * Daily execution at a specific time
   */
  case class Daily(hour: Int, minute: Int, initialExecutionTime: Option[ExecutionTime] = None) extends ScheduleType {
    require(hour >= 0 && hour < 24, "Hour must be between 0 and 23")
    require(minute >= 0 && minute < 60, "Minute must be between 0 and 59")
  }
}

