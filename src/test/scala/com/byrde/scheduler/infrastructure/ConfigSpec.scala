package com.byrde.scheduler.infrastructure

import com.google.auth.oauth2.GoogleCredentials
import org.byrde.pubsub.conf.{PubSubConfig => CommonsPubSubConfig}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.mockito.Mockito._

class ConfigSpec extends AnyFlatSpec with Matchers {
  
  // Mock credentials for testing
  private val mockCredentials = mock(classOf[GoogleCredentials])
  
  val validDatabaseConfig = DatabaseConfig(
    url = "jdbc:postgresql://localhost:5432/scheduler",
    maxPoolSize = 10
  )
  
  val validPubSubConfig = CommonsPubSubConfig(
    project = "my-project",
    credentials = mockCredentials
  )
  
  val validSubscriptionName = "schedule-requests"
  
  val validSchedulerConfig = SchedulerConfig(
    maxThreads = 10,
    pollingIntervalSeconds = 10
  )
  
  val validApiConfig = ApiConfig(
    port = 8081,
    username = None,
    password = None
  )
  
  "Config.validate" should "accept valid configuration" in {
    val config = AppConfig(
      database = validDatabaseConfig,
      pubsub = validPubSubConfig,
      pubsubSubscription = validSubscriptionName,
      scheduler = validSchedulerConfig,
      api = validApiConfig
    )
    
    val result = Config.validate(config)
    result.isRight shouldBe true
  }
  
  it should "reject empty database URL" in {
    val config = AppConfig(
      database = validDatabaseConfig.copy(url = ""),
      pubsub = validPubSubConfig,
      pubsubSubscription = validSubscriptionName,
      scheduler = validSchedulerConfig,
      api = validApiConfig
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
      pubsub = validPubSubConfig.copy(project = ""),
      pubsubSubscription = validSubscriptionName,
      scheduler = validSchedulerConfig,
      api = validApiConfig
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
      pubsub = validPubSubConfig,
      pubsubSubscription = "",
      scheduler = validSchedulerConfig,
      api = validApiConfig
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
  
  "CommonsPubSubConfig" should "store project info" in {
    val config = CommonsPubSubConfig(
      project = "test-project",
      credentials = mockCredentials
    )
    
    config.project shouldBe "test-project"
  }
  
  it should "support optional emulator host" in {
    val config = CommonsPubSubConfig(
      project = "test-project",
      credentials = mockCredentials,
      hostOpt = Some("localhost:8085")
    )
    
    config.hostOpt shouldBe Some("localhost:8085")
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
  
  "ApiConfig" should "indicate auth is disabled when credentials are not set" in {
    val config = ApiConfig(port = 8081, username = None, password = None)
    config.isAuthEnabled shouldBe false
  }
  
  it should "indicate auth is disabled when only username is set" in {
    val config = ApiConfig(port = 8081, username = Some("admin"), password = None)
    config.isAuthEnabled shouldBe false
  }
  
  it should "indicate auth is disabled when only password is set" in {
    val config = ApiConfig(port = 8081, username = None, password = Some("secret"))
    config.isAuthEnabled shouldBe false
  }
  
  it should "indicate auth is enabled when both username and password are set" in {
    val config = ApiConfig(port = 8081, username = Some("admin"), password = Some("secret"))
    config.isAuthEnabled shouldBe true
  }
  
  it should "store port configuration" in {
    ApiConfig(port = 9000, username = None, password = None).port shouldBe 9000
  }
}
