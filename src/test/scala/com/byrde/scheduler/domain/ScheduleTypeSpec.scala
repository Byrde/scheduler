package com.byrde.scheduler.domain

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class ScheduleTypeSpec extends AnyFlatSpec with Matchers {
  
  val futureTime = Instant.now().plusSeconds(3600)
  val futureExecutionTime = ExecutionTime(futureTime)
  
  "ScheduleType.OneTime" should "store execution time" in {
    val oneTime = ScheduleType.OneTime(futureExecutionTime)
    
    oneTime.executionTime shouldBe futureExecutionTime
    oneTime match {
      case ScheduleType.OneTime(execTime) => execTime shouldBe futureExecutionTime
      case _ => fail("Should be OneTime")
    }
  }
  
  "ScheduleType.Cron" should "store cron expression and optional initial time" in {
    val cronExpr = "0 0 * * *"
    val cron = ScheduleType.Cron(cronExpr, Some(futureExecutionTime))
    
    cron.cronExpression shouldBe cronExpr
    cron.initialExecutionTime shouldBe Some(futureExecutionTime)
  }
  
  it should "work without initial execution time" in {
    val cron = ScheduleType.Cron("0 0 * * *", None)
    
    cron.initialExecutionTime shouldBe None
  }
  
  "ScheduleType.FixedDelay" should "store delay in seconds" in {
    val delay = ScheduleType.FixedDelay(3600, Some(futureExecutionTime))
    
    delay.delaySeconds shouldBe 3600
    delay.initialExecutionTime shouldBe Some(futureExecutionTime)
  }
  
  it should "accept various delay values" in {
    ScheduleType.FixedDelay(1, None).delaySeconds shouldBe 1
    ScheduleType.FixedDelay(60, None).delaySeconds shouldBe 60
    ScheduleType.FixedDelay(3600, None).delaySeconds shouldBe 3600
    ScheduleType.FixedDelay(86400, None).delaySeconds shouldBe 86400
  }
  
  "ScheduleType.Daily" should "store hour and minute" in {
    val daily = ScheduleType.Daily(9, 30, Some(futureExecutionTime))
    
    daily.hour shouldBe 9
    daily.minute shouldBe 30
    daily.initialExecutionTime shouldBe Some(futureExecutionTime)
  }
  
  it should "accept boundary hour and minute values" in {
    noException should be thrownBy ScheduleType.Daily(0, 0, None)
    noException should be thrownBy ScheduleType.Daily(23, 59, None)
  }
  
  it should "validate hour range" in {
    an[IllegalArgumentException] should be thrownBy ScheduleType.Daily(-1, 0, None)
    an[IllegalArgumentException] should be thrownBy ScheduleType.Daily(24, 0, None)
  }
  
  it should "validate minute range" in {
    an[IllegalArgumentException] should be thrownBy ScheduleType.Daily(12, -1, None)
    an[IllegalArgumentException] should be thrownBy ScheduleType.Daily(12, 60, None)
  }
  
  "ScheduleType pattern matching" should "distinguish between types" in {
    val oneTime = ScheduleType.OneTime(futureExecutionTime)
    val cron = ScheduleType.Cron("0 0 * * *", None)
    val fixedDelay = ScheduleType.FixedDelay(3600, None)
    val daily = ScheduleType.Daily(9, 0, None)
    
    def identify(st: ScheduleType): String = st match {
      case ScheduleType.OneTime(_) => "one-time"
      case ScheduleType.Cron(_, _) => "cron"
      case ScheduleType.FixedDelay(_, _) => "fixed-delay"
      case ScheduleType.Daily(_, _, _) => "daily"
    }
    
    identify(oneTime) shouldBe "one-time"
    identify(cron) shouldBe "cron"
    identify(fixedDelay) shouldBe "fixed-delay"
    identify(daily) shouldBe "daily"
  }
}

