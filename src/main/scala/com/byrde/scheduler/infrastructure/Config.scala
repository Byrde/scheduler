package com.byrde.scheduler.infrastructure

import com.typesafe.config.ConfigFactory

/**
 * Application configuration loaded from environment variables and application.conf
 */
case class AppConfig(
  database: DatabaseConfig,
  pubsub: PubSubConfig,
  scheduler: SchedulerConfig
)

case class DatabaseConfig(
  url: String,
  maxPoolSize: Int
)

case class PubSubConfig(
  projectId: String,
  subscriptionName: String,
  credentialsPath: Option[String]
)

case class SchedulerConfig(
  maxThreads: Int,
  pollingIntervalSeconds: Int
)

object Config {
  def load(): AppConfig = {
    val config = ConfigFactory.load()
    
    // Load from environment variables with fallback to config file
    val databaseUrl = sys.env.getOrElse(
      "DATABASE_URL",
      config.getString("database.url")
    )
    
    val pubsubProjectId = sys.env.getOrElse(
      "PUBSUB_PROJECT_ID",
      config.getString("pubsub.project-id")
    )
    
    val pubsubSubscription = sys.env.getOrElse(
      "PUBSUB_SUBSCRIPTION",
      config.getString("pubsub.subscription")
    )
    
    val pubsubCredentialsPath = sys.env.get("PUBSUB_CREDENTIALS_PATH")
      .orElse(if (config.hasPath("pubsub.credentials-path")) {
        Some(config.getString("pubsub.credentials-path"))
      } else None)
    
    val maxThreads = sys.env.get("MAX_THREADS")
      .map(_.toInt)
      .getOrElse(config.getInt("scheduler.max-threads"))
    
    val pollingInterval = sys.env.get("POLLING_INTERVAL_SECONDS")
      .map(_.toInt)
      .getOrElse(config.getInt("scheduler.polling-interval-seconds"))
    
    AppConfig(
      database = DatabaseConfig(
        url = databaseUrl,
        maxPoolSize = config.getInt("database.max-pool-size")
      ),
      pubsub = PubSubConfig(
        projectId = pubsubProjectId,
        subscriptionName = pubsubSubscription,
        credentialsPath = pubsubCredentialsPath
      ),
      scheduler = SchedulerConfig(
        maxThreads = maxThreads,
        pollingIntervalSeconds = pollingInterval
      )
    )
  }
  
  def validate(config: AppConfig): Either[String, Unit] = {
    if (config.database.url.isEmpty) {
      Left("DATABASE_URL is required")
    } else if (config.pubsub.projectId.isEmpty) {
      Left("PUBSUB_PROJECT_ID is required")
    } else if (config.pubsub.subscriptionName.isEmpty) {
      Left("PUBSUB_SUBSCRIPTION is required")
    } else {
      Right(())
    }
  }
}

