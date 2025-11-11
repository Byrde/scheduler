package com.byrde.scheduler.domain

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class ScheduledMessageSpec extends AnyFlatSpec with Matchers {
  
  val taskId = TaskId.generate()
  val executionTime = ExecutionTime(Instant.now().plusSeconds(3600))
  val topic = TargetTopic("test-topic")
  val payload = MessagePayload.fromString("test message")
  val scheduleType = ScheduleType.OneTime(executionTime)
  
  "ScheduledMessage" should "be created with all required fields" in {
    val message = ScheduledMessage(
      taskId = taskId,
      executionTime = executionTime,
      targetTopic = topic,
      payload = payload,
      status = TaskStatus.Scheduled,
      scheduleType = scheduleType
    )
    
    message.taskId shouldBe taskId
    message.executionTime shouldBe executionTime
    message.targetTopic shouldBe topic
    message.payload shouldBe payload
    message.status shouldBe TaskStatus.Scheduled
    message.scheduleType shouldBe scheduleType
  }
  
  it should "support different task statuses" in {
    val statuses = List(
      TaskStatus.Scheduled,
      TaskStatus.Executing,
      TaskStatus.Completed,
      TaskStatus.Failed
    )
    
    statuses.foreach { status =>
      val message = ScheduledMessage(
        taskId = taskId,
        executionTime = executionTime,
        targetTopic = topic,
        payload = payload,
        status = status,
        scheduleType = scheduleType
      )
      
      message.status shouldBe status
    }
  }
  
  it should "support all schedule types" in {
    val scheduleTypes = List(
      ScheduleType.OneTime(executionTime),
      ScheduleType.Cron("0 0 * * *", Some(executionTime)),
      ScheduleType.FixedDelay(3600, Some(executionTime)),
      ScheduleType.Daily(9, 0, Some(executionTime))
    )
    
    scheduleTypes.foreach { st =>
      val message = ScheduledMessage(
        taskId = taskId,
        executionTime = executionTime,
        targetTopic = topic,
        payload = payload,
        status = TaskStatus.Scheduled,
        scheduleType = st
      )
      
      message.scheduleType shouldBe st
    }
  }
  
  "ScheduledMessage with full GCP topic path" should "maintain path" in {
    val gcpTopic = TargetTopic("projects/my-project/topics/my-topic")
    val message = ScheduledMessage(
      taskId = taskId,
      executionTime = executionTime,
      targetTopic = gcpTopic,
      payload = payload,
      status = TaskStatus.Scheduled,
      scheduleType = scheduleType
    )
    
    message.targetTopic.name shouldBe "projects/my-project/topics/my-topic"
  }
  
  "ScheduledMessage payload" should "preserve data and attributes" in {
    val payloadWithAttributes = MessagePayload(
      "test data".getBytes("UTF-8"),
      Map("priority" -> "high", "type" -> "notification")
    )
    
    val message = ScheduledMessage(
      taskId = taskId,
      executionTime = executionTime,
      targetTopic = topic,
      payload = payloadWithAttributes,
      status = TaskStatus.Scheduled,
      scheduleType = scheduleType
    )
    
    message.payload.dataAsString shouldBe "test data"
    message.payload.attributes shouldBe Map("priority" -> "high", "type" -> "notification")
  }
}

