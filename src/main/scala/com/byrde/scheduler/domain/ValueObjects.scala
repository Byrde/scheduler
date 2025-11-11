package com.byrde.scheduler.domain

import java.time.Instant

/**
 * Value object representing when a scheduled message should be executed.
 */
final case class ExecutionTime(instant: Instant, requireFuture: Boolean = true) {
  if (requireFuture) {
    require(instant.isAfter(Instant.now()), "Execution time must be in the future")
  }
  
  def isReady: Boolean = !instant.isAfter(Instant.now())
}

object ExecutionTime {
  def fromEpochMillis(millis: Long, requireFuture: Boolean = true): Either[String, ExecutionTime] = {
    try {
      val instant = Instant.ofEpochMilli(millis)
      if (!requireFuture || instant.isAfter(Instant.now())) {
        Right(ExecutionTime(instant, requireFuture))
      } else {
        Left("Execution time must be in the future")
      }
    } catch {
      case _: Exception => Left("Invalid timestamp")
    }
  }
  
  def fromIso8601(isoString: String, requireFuture: Boolean = true): Either[String, ExecutionTime] = {
    try {
      val instant = Instant.parse(isoString)
      if (!requireFuture || instant.isAfter(Instant.now())) {
        Right(ExecutionTime(instant, requireFuture))
      } else {
        Left("Execution time must be in the future")
      }
    } catch {
      case _: Exception => Left("Invalid ISO-8601 timestamp")
    }
  }
  
  def now(): ExecutionTime = ExecutionTime(Instant.now(), requireFuture = false)
}

/**
 * Value object representing the target Pub/Sub topic for message delivery.
 */
final case class TargetTopic(name: String) {
  require(name.nonEmpty, "Topic name cannot be empty")
  require(isValid, s"Invalid topic name format: $name")
  
  private def isValid: Boolean = {
    // GCP topic format: projects/{project}/topics/{topic}
    // Allow both full format and simple topic name
    name.matches("^projects/[^/]+/topics/[^/]+$") || 
    name.matches("^[a-zA-Z][a-zA-Z0-9-_.~+%]{2,254}$")
  }
  
  def toFullName(projectId: String): String = {
    if (name.startsWith("projects/")) name
    else s"projects/$projectId/topics/$name"
  }
}

/**
 * Value object representing the message payload to be published.
 */
final case class MessagePayload(data: Array[Byte], attributes: Map[String, String] = Map.empty) {
  require(data.nonEmpty, "Payload data cannot be empty")
  
  def dataAsString: String = new String(data, "UTF-8")
  
  override def equals(obj: Any): Boolean = obj match {
    case that: MessagePayload =>
      java.util.Arrays.equals(this.data, that.data) && this.attributes == that.attributes
    case _ => false
  }
  
  override def hashCode(): Int = {
    val prime = 31
    var result = 1
    result = prime * result + java.util.Arrays.hashCode(data)
    result = prime * result + attributes.hashCode()
    result
  }
}

object MessagePayload {
  def fromString(text: String, attributes: Map[String, String] = Map.empty): MessagePayload = {
    MessagePayload(text.getBytes("UTF-8"), attributes)
  }
}

/**
 * Value object representing a unique identifier for a scheduled task.
 */
final case class TaskId(value: String) {
  require(value.nonEmpty, "Task ID cannot be empty")
}

object TaskId {
  def generate(): TaskId = TaskId(java.util.UUID.randomUUID().toString)
}

/**
 * Value object representing the status of a scheduled task.
 */
sealed trait TaskStatus

object TaskStatus {
  case object Scheduled extends TaskStatus
  case object Executing extends TaskStatus
  case object Completed extends TaskStatus
  case object Failed extends TaskStatus
}

