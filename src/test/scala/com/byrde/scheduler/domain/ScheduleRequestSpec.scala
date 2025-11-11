package com.byrde.scheduler.domain

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class ScheduleRequestSpec extends AnyFlatSpec with Matchers {
  
  val futureTime = Instant.now().plusSeconds(3600)
  val futureExecutionTime = ExecutionTime(futureTime)
  val validTopic = TargetTopic("test-topic")
  val validPayload = MessagePayload.fromString("test message")
  
  "ScheduleRequest.create" should "create a valid one-time schedule request" in {
    val result = ScheduleRequest.create(
      executionTimeMillis = futureTime.toEpochMilli,
      topicName = "test-topic",
      payloadData = "test message".getBytes("UTF-8"),
      payloadAttributes = Map("key" -> "value")
    )
    
    result.isRight shouldBe true
    result.foreach { request =>
      request.targetTopic.name shouldBe "test-topic"
      request.payload.dataAsString shouldBe "test message"
      request.scheduleType shouldBe a[ScheduleType.OneTime]
      request.isOneTime shouldBe true
      request.isRecurring shouldBe false
    }
  }
  
  it should "reject past execution times" in {
    val pastTime = Instant.now().minusSeconds(3600)
    val result = ScheduleRequest.create(
      executionTimeMillis = pastTime.toEpochMilli,
      topicName = "test-topic",
      payloadData = "test".getBytes("UTF-8"),
      payloadAttributes = Map.empty
    )
    
    result.isLeft shouldBe true
    result.left.foreach { error =>
      error should include("future")
    }
  }
  
  it should "reject empty topic names" in {
    val result = ScheduleRequest.create(
      executionTimeMillis = futureTime.toEpochMilli,
      topicName = "",
      payloadData = "test".getBytes("UTF-8"),
      payloadAttributes = Map.empty
    )
    
    result.isLeft shouldBe true
  }
  
  it should "reject empty payload" in {
    val result = ScheduleRequest.create(
      executionTimeMillis = futureTime.toEpochMilli,
      topicName = "test-topic",
      payloadData = Array.empty,
      payloadAttributes = Map.empty
    )
    
    result.isLeft shouldBe true
  }
  
  "ScheduleRequest with OneTime" should "convert to ScheduledMessage correctly" in {
    val request = ScheduleRequest(
      scheduleType = ScheduleType.OneTime(futureExecutionTime),
      targetTopic = validTopic,
      payload = validPayload
    )
    
    val taskId = TaskId.generate()
    val message = request.toScheduledMessage(taskId)
    
    message.taskId shouldBe taskId
    message.executionTime shouldBe futureExecutionTime
    message.targetTopic shouldBe validTopic
    message.payload shouldBe validPayload
    message.status shouldBe TaskStatus.Scheduled
    message.scheduleType shouldBe request.scheduleType
  }
  
  "ScheduleRequest with Cron" should "be identified as recurring" in {
    val request = ScheduleRequest(
      scheduleType = ScheduleType.Cron("0 0 * * *", Some(futureExecutionTime)),
      targetTopic = validTopic,
      payload = validPayload,
      taskName = Some("daily-job")
    )
    
    request.isOneTime shouldBe false
    request.isRecurring shouldBe true
    request.taskName shouldBe Some("daily-job")
  }
  
  "ScheduleRequest with FixedDelay" should "be identified as recurring" in {
    val request = ScheduleRequest(
      scheduleType = ScheduleType.FixedDelay(3600, Some(futureExecutionTime)),
      targetTopic = validTopic,
      payload = validPayload
    )
    
    request.isRecurring shouldBe true
  }
  
  "ScheduleRequest with Daily" should "be identified as recurring" in {
    val request = ScheduleRequest(
      scheduleType = ScheduleType.Daily(9, 0, Some(futureExecutionTime)),
      targetTopic = validTopic,
      payload = validPayload
    )
    
    request.isRecurring shouldBe true
  }
  
  "ScheduleRequest with recurring type without initial time" should "default to near future" in {
    val request = ScheduleRequest(
      scheduleType = ScheduleType.Cron("0 0 * * *", None),
      targetTopic = validTopic,
      payload = validPayload
    )
    
    val taskId = TaskId.generate()
    val message = request.toScheduledMessage(taskId)
    
    // Should default to ~1 minute from now
    message.executionTime.instant.isAfter(Instant.now()) shouldBe true
    message.executionTime.instant.isBefore(Instant.now().plusSeconds(120)) shouldBe true
  }
}

