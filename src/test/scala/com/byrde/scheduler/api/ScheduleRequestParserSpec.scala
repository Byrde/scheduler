package com.byrde.scheduler.api

import com.byrde.scheduler.domain._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant
import java.util.Base64

class ScheduleRequestParserSpec extends AnyFlatSpec with Matchers {
  
  val futureTime = Instant.now().plusSeconds(3600).toEpochMilli
  val base64Payload = Base64.getEncoder.encodeToString("test message".getBytes("UTF-8"))
  
  "ScheduleRequestParser" should "parse valid one-time schedule request" in {
    val json =
      s"""{
        |  "executionTime": $futureTime,
        |  "targetTopic": "test-topic",
        |  "payload": {
        |    "data": "$base64Payload",
        |    "attributes": {
        |      "key1": "value1"
        |    }
        |  }
        |}""".stripMargin
    
    val result = ScheduleRequestParser.parse(json)
    
    result.isRight shouldBe true
    result.foreach { request =>
      request.targetTopic.name shouldBe "test-topic"
      request.payload.dataAsString shouldBe "test message"
      request.payload.attributes shouldBe Map("key1" -> "value1")
      request.scheduleType shouldBe a[ScheduleType.OneTime]
    }
  }
  
  it should "parse request with empty attributes" in {
    val json =
      s"""{
        |  "executionTime": $futureTime,
        |  "targetTopic": "test-topic",
        |  "payload": {
        |    "data": "$base64Payload",
        |    "attributes": {}
        |  }
        |}""".stripMargin
    
    val result = ScheduleRequestParser.parse(json)
    
    result.isRight shouldBe true
    result.foreach { request =>
      request.payload.attributes shouldBe empty
    }
  }
  
  it should "parse request with full GCP topic path" in {
    val json =
      s"""{
        |  "executionTime": $futureTime,
        |  "targetTopic": "projects/my-project/topics/my-topic",
        |  "payload": {
        |    "data": "$base64Payload",
        |    "attributes": {}
        |  }
        |}""".stripMargin
    
    val result = ScheduleRequestParser.parse(json)
    
    result.isRight shouldBe true
    result.foreach { request =>
      request.targetTopic.name shouldBe "projects/my-project/topics/my-topic"
    }
  }
  
  it should "reject invalid JSON" in {
    val invalidJson = "{ this is not valid json }"
    
    val result = ScheduleRequestParser.parse(invalidJson)
    
    result.isLeft shouldBe true
    result.left.foreach { error =>
      error should include("JSON")
    }
  }
  
  it should "reject missing required fields" in {
    val json = """{"executionTime": 1234567890}"""
    
    val result = ScheduleRequestParser.parse(json)
    
    result.isLeft shouldBe true
  }
  
  it should "reject past execution time" in {
    val pastTime = Instant.now().minusSeconds(3600).toEpochMilli
    val json =
      s"""{
        |  "executionTime": $pastTime,
        |  "targetTopic": "test-topic",
        |  "payload": {
        |    "data": "$base64Payload",
        |    "attributes": {}
        |  }
        |}""".stripMargin
    
    val result = ScheduleRequestParser.parse(json)
    
    result.isLeft shouldBe true
    result.left.foreach { error =>
      error should include("future")
    }
  }
  
  it should "reject empty topic name" in {
    val json =
      s"""{
        |  "executionTime": $futureTime,
        |  "targetTopic": "",
        |  "payload": {
        |    "data": "$base64Payload",
        |    "attributes": {}
        |  }
        |}""".stripMargin
    
    val result = ScheduleRequestParser.parse(json)
    
    result.isLeft shouldBe true
  }
  
  it should "reject empty payload data" in {
    val emptyPayload = Base64.getEncoder.encodeToString(Array.empty[Byte])
    val json =
      s"""{
        |  "executionTime": $futureTime,
        |  "targetTopic": "test-topic",
        |  "payload": {
        |    "data": "$emptyPayload",
        |    "attributes": {}
        |  }
        |}""".stripMargin
    
    val result = ScheduleRequestParser.parse(json)
    
    result.isLeft shouldBe true
  }
  
  it should "reject malformed base64 payload" in {
    val json =
      s"""{
        |  "executionTime": $futureTime,
        |  "targetTopic": "test-topic",
        |  "payload": {
        |    "data": "this-is-not-valid-base64!!!",
        |    "attributes": {}
        |  }
        |}""".stripMargin
    
    val result = ScheduleRequestParser.parse(json)
    
    result.isLeft shouldBe true
  }
  
  it should "handle payload with multiple attributes" in {
    val json =
      s"""{
        |  "executionTime": $futureTime,
        |  "targetTopic": "test-topic",
        |  "payload": {
        |    "data": "$base64Payload",
        |    "attributes": {
        |      "priority": "high",
        |      "type": "notification",
        |      "source": "scheduler",
        |      "version": "1.0"
        |    }
        |  }
        |}""".stripMargin
    
    val result = ScheduleRequestParser.parse(json)
    
    result.isRight shouldBe true
    result.foreach { request =>
      request.payload.attributes should have size 4
      request.payload.attributes("priority") shouldBe "high"
      request.payload.attributes("type") shouldBe "notification"
      request.payload.attributes("source") shouldBe "scheduler"
      request.payload.attributes("version") shouldBe "1.0"
    }
  }
}

