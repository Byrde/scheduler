package com.byrde.scheduler.infrastructure

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigSpec extends AnyFlatSpec with Matchers {
  
  val validDatabaseConfig = DatabaseConfig(
    url = "jdbc:postgresql://localhost:5432/scheduler",
    maxPoolSize = 10
  )
  
  val validPubSubConfig = PubSubConfig(
    projectId = "my-project",
    subscriptionName = "schedule-requests",
    credentialsPath = Some("/path/to/credentials.json")
  )
  
  val validSchedulerConfig = SchedulerConfig(
    maxThreads = 10,
    pollingIntervalSeconds = 10
  )
  
  "Config.validate" should "accept valid configuration" in {
    val config = AppConfig(
      database = validDatabaseConfig,
      pubsub = validPubSubConfig,
      scheduler = validSchedulerConfig
    )
    
    val result = Config.validate(config)
    result.isRight shouldBe true
  }
  
  it should "accept configuration without credentials path" in {
    val configWithoutCreds = AppConfig(
      database = validDatabaseConfig,
      pubsub = validPubSubConfig.copy(credentialsPath = None),
      scheduler = validSchedulerConfig
    )
    
    val result = Config.validate(configWithoutCreds)
    result.isRight shouldBe true
  }
  
  it should "reject empty database URL" in {
    val config = AppConfig(
      database = validDatabaseConfig.copy(url = ""),
      pubsub = validPubSubConfig,
      scheduler = validSchedulerConfig
    )
    
    val result = Config.validate(config)
    result.isLeft shouldBe true
    result.left.foreach { error =>
      error should include("DATABASE_URL")
    }
  }
  
  it should "reject empty project ID" in {
    val config = AppConfig(
      database = validDatabaseConfig,
      pubsub = validPubSubConfig.copy(projectId = ""),
      scheduler = validSchedulerConfig
    )
    
    val result = Config.validate(config)
    result.isLeft shouldBe true
    result.left.foreach { error =>
      error should include("PUBSUB_PROJECT_ID")
    }
  }
  
  it should "reject empty subscription name" in {
    val config = AppConfig(
      database = validDatabaseConfig,
      pubsub = validPubSubConfig.copy(subscriptionName = ""),
      scheduler = validSchedulerConfig
    )
    
    val result = Config.validate(config)
    result.isLeft shouldBe true
    result.left.foreach { error =>
      error should include("PUBSUB_SUBSCRIPTION")
    }
  }
  
  "DatabaseConfig" should "store JDBC URL and pool size" in {
    val config = DatabaseConfig(
      url = "jdbc:postgresql://localhost:5432/test",
      maxPoolSize = 20
    )
    
    config.url shouldBe "jdbc:postgresql://localhost:5432/test"
    config.maxPoolSize shouldBe 20
  }
  
  it should "support different database types" in {
    val postgresConfig = DatabaseConfig("jdbc:postgresql://localhost/db", 10)
    val mysqlConfig = DatabaseConfig("jdbc:mysql://localhost/db", 10)
    val sqlserverConfig = DatabaseConfig("jdbc:sqlserver://localhost/db", 10)
    
    postgresConfig.url should startWith("jdbc:postgresql:")
    mysqlConfig.url should startWith("jdbc:mysql:")
    sqlserverConfig.url should startWith("jdbc:sqlserver:")
  }
  
  "PubSubConfig" should "store project and subscription info" in {
    val config = PubSubConfig(
      projectId = "test-project",
      subscriptionName = "test-subscription",
      credentialsPath = Some("/path/to/creds.json")
    )
    
    config.projectId shouldBe "test-project"
    config.subscriptionName shouldBe "test-subscription"
    config.credentialsPath shouldBe Some("/path/to/creds.json")
  }
  
  it should "allow optional credentials path" in {
    val config = PubSubConfig(
      projectId = "test-project",
      subscriptionName = "test-subscription",
      credentialsPath = None
    )
    
    config.credentialsPath shouldBe None
  }
  
  "SchedulerConfig" should "store thread and polling configuration" in {
    val config = SchedulerConfig(
      maxThreads = 15,
      pollingIntervalSeconds = 5
    )
    
    config.maxThreads shouldBe 15
    config.pollingIntervalSeconds shouldBe 5
  }
  
  it should "support various thread pool sizes" in {
    SchedulerConfig(1, 10).maxThreads shouldBe 1
    SchedulerConfig(10, 10).maxThreads shouldBe 10
    SchedulerConfig(50, 10).maxThreads shouldBe 50
  }
  
  it should "support various polling intervals" in {
    SchedulerConfig(10, 1).pollingIntervalSeconds shouldBe 1
    SchedulerConfig(10, 10).pollingIntervalSeconds shouldBe 10
    SchedulerConfig(10, 60).pollingIntervalSeconds shouldBe 60
  }
}

