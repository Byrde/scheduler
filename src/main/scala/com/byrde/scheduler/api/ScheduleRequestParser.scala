package com.byrde.scheduler.api

import com.byrde.scheduler.domain.{ExecutionTime, ScheduleRequest, ScheduleType}
import io.circe.parser.decode
import io.circe.{Decoder, HCursor}

import java.util.Base64

/**
 * JSON schema for schedule request messages with full schedule type support
 */

// Schedule configuration
case class ScheduleConfigMessage(
  scheduleType: String,
  executionTime: Option[Long] = None,          // for one-time
  expression: Option[String] = None,            // for cron
  delaySeconds: Option[Long] = None,           // for fixed-delay
  hour: Option[Int] = None,                     // for daily
  minute: Option[Int] = None,                   // for daily
  initialExecutionTime: Option[Long] = None     // for recurring schedules
)

object ScheduleConfigMessage {
  implicit val decoder: Decoder[ScheduleConfigMessage] = (c: HCursor) => {
    for {
      scheduleType <- c.downField("type").as[String]
      executionTime <- c.downField("executionTime").as[Option[Long]]
      expression <- c.downField("expression").as[Option[String]]
      delaySeconds <- c.downField("delaySeconds").as[Option[Long]]
      hour <- c.downField("hour").as[Option[Int]]
      minute <- c.downField("minute").as[Option[Int]]
      initialExecutionTime <- c.downField("initialExecutionTime").as[Option[Long]]
    } yield ScheduleConfigMessage(
      scheduleType, executionTime, expression, delaySeconds, 
      hour, minute, initialExecutionTime
    )
  }
}

// Payload message
case class PayloadMessage(
  data: String,  // Base64 encoded
  attributes: Map[String, String] = Map.empty
)

object PayloadMessage {
  implicit val decoder: Decoder[PayloadMessage] = (c: HCursor) => {
    for {
      data <- c.downField("data").as[String]
      attributes <- c.downField("attributes").as[Option[Map[String, String]]]
    } yield PayloadMessage(data, attributes.getOrElse(Map.empty))
  }
}

// Full schedule request message
case class ScheduleRequestMessage(
  schedule: ScheduleConfigMessage,
  targetTopic: String,
  payload: PayloadMessage,
  taskName: Option[String] = None
)

object ScheduleRequestMessage {
  implicit val decoder: Decoder[ScheduleRequestMessage] = (c: HCursor) => {
    for {
      schedule <- c.downField("schedule").as[ScheduleConfigMessage]
      targetTopic <- c.downField("targetTopic").as[String]
      payload <- c.downField("payload").as[PayloadMessage]
      taskName <- c.downField("taskName").as[Option[String]]
    } yield ScheduleRequestMessage(schedule, targetTopic, payload, taskName)
  }
}

// Legacy format (one-time only)
case class LegacyScheduleRequestMessage(
  executionTime: Long,
  targetTopic: String,
  payload: PayloadMessage
)

object LegacyScheduleRequestMessage {
  implicit val decoder: Decoder[LegacyScheduleRequestMessage] = (c: HCursor) => {
    for {
      executionTime <- c.downField("executionTime").as[Long]
      targetTopic <- c.downField("targetTopic").as[String]
      payload <- c.downField("payload").as[PayloadMessage]
    } yield LegacyScheduleRequestMessage(executionTime, targetTopic, payload)
  }
}

/**
 * Parses and validates schedule request messages
 */
object ScheduleRequestParser {
  
  /**
   * Parses a JSON message into a domain ScheduleRequest.
   * Supports both new format (with schedule types) and legacy format.
   */
  def parse(jsonMessage: String): Either[String, ScheduleRequest] = {
    // Try new format first, fall back to legacy format
    parseNewFormat(jsonMessage).orElse(parseLegacyFormat(jsonMessage))
  }
  
  /**
   * Parses new format with full schedule support
   */
  private def parseNewFormat(jsonMessage: String): Either[String, ScheduleRequest] = {
    for {
      message <- decode[ScheduleRequestMessage](jsonMessage)
        .left.map(err => s"Invalid JSON format: ${err.getMessage}")
      payloadBytes <- decodeBase64(message.payload.data)
      scheduleType <- parseScheduleType(message.schedule)
      request <- ScheduleRequest.createWithSchedule(
        scheduleType = scheduleType,
        topicName = message.targetTopic,
        payloadData = payloadBytes,
        payloadAttributes = message.payload.attributes,
        taskName = message.taskName
      )
    } yield request
  }
  
  /**
   * Parses legacy format (one-time only) for backward compatibility
   */
  private def parseLegacyFormat(jsonMessage: String): Either[String, ScheduleRequest] = {
    for {
      message <- decode[LegacyScheduleRequestMessage](jsonMessage)
        .left.map(err => s"Invalid legacy JSON format: ${err.getMessage}")
      payloadBytes <- decodeBase64(message.payload.data)
      request <- ScheduleRequest.create(
        executionTimeMillis = message.executionTime,
        topicName = message.targetTopic,
        payloadData = payloadBytes,
        payloadAttributes = message.payload.attributes
      )
    } yield request
  }
  
  private def parseScheduleType(config: ScheduleConfigMessage): Either[String, ScheduleType] = {
    config.scheduleType.toLowerCase match {
      case "one-time" =>
        config.executionTime match {
          case Some(millis) =>
            ExecutionTime.fromEpochMillis(millis).map(ScheduleType.OneTime)
          case None =>
            Left("executionTime is required for one-time schedule")
        }
      
      case "cron" =>
        config.expression match {
          case Some(expr) =>
            val initialTime = config.initialExecutionTime.flatMap { millis =>
              ExecutionTime.fromEpochMillis(millis, requireFuture = false).toOption
            }
            try {
              Right(ScheduleType.Cron(expr, initialTime))
            } catch {
              case e: IllegalArgumentException => Left(s"Invalid cron schedule: ${e.getMessage}")
            }
          case None =>
            Left("expression is required for cron schedule")
        }
      
      case "fixed-delay" =>
        config.delaySeconds match {
          case Some(delay) =>
            val initialTime = config.initialExecutionTime.flatMap { millis =>
              ExecutionTime.fromEpochMillis(millis, requireFuture = false).toOption
            }
            try {
              Right(ScheduleType.FixedDelay(delay, initialTime))
            } catch {
              case e: IllegalArgumentException => Left(s"Invalid fixed-delay schedule: ${e.getMessage}")
            }
          case None =>
            Left("delaySeconds is required for fixed-delay schedule")
        }
      
      case "daily" =>
        (config.hour, config.minute) match {
          case (Some(h), Some(m)) =>
            val initialTime = config.initialExecutionTime.flatMap { millis =>
              ExecutionTime.fromEpochMillis(millis, requireFuture = false).toOption
            }
            try {
              Right(ScheduleType.Daily(h, m, initialTime))
            } catch {
              case e: IllegalArgumentException => Left(s"Invalid daily schedule: ${e.getMessage}")
            }
          case _ =>
            Left("hour and minute are required for daily schedule")
        }
      
      case unknown =>
        Left(s"Unknown schedule type: $unknown. Supported types: one-time, cron, fixed-delay, daily")
    }
  }
  
  private def decodeBase64(base64String: String): Either[String, Array[Byte]] = {
    try {
      Right(Base64.getDecoder.decode(base64String))
    } catch {
      case ex: Exception => Left(s"Invalid base64 encoding: ${ex.getMessage}")
    }
  }
}

