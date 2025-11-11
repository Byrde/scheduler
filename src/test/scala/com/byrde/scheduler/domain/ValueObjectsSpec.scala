package com.byrde.scheduler.domain

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class ValueObjectsSpec extends AnyFlatSpec with Matchers {
  
  "ExecutionTime" should "accept future timestamps" in {
    val futureMillis = Instant.now().plusSeconds(3600).toEpochMilli
    val result = ExecutionTime.fromEpochMillis(futureMillis)
    result.isRight shouldBe true
  }
  
  it should "reject past timestamps" in {
    val pastMillis = Instant.now().minusSeconds(3600).toEpochMilli
    val result = ExecutionTime.fromEpochMillis(pastMillis)
    result.isLeft shouldBe true
  }
  
  "TargetTopic" should "accept valid simple topic names" in {
    noException should be thrownBy TargetTopic("my-topic")
  }
  
  it should "accept full GCP topic paths" in {
    noException should be thrownBy TargetTopic("projects/my-project/topics/my-topic")
  }
  
  it should "reject empty topic names" in {
    an[IllegalArgumentException] should be thrownBy TargetTopic("")
  }
  
  "MessagePayload" should "create from string" in {
    val payload = MessagePayload.fromString("test message")
    payload.dataAsString shouldBe "test message"
  }
  
  it should "reject empty data" in {
    an[IllegalArgumentException] should be thrownBy MessagePayload(Array.empty, Map.empty)
  }
  
  "TaskId" should "generate unique identifiers" in {
    val id1 = TaskId.generate()
    val id2 = TaskId.generate()
    id1.value should not equal id2.value
  }
}

