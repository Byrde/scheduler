package com.byrde.scheduler.infrastructure

import com.google.auth.oauth2.{GoogleCredentials, ServiceAccountCredentials}
import com.typesafe.config.ConfigFactory
import org.byrde.pubsub.conf.{PubSubConfig => CommonsPubSubConfig}

import java.io.FileInputStream

/**
 * Application configuration loaded from environment variables and application.conf
 */
case class AppConfig(
  database: DatabaseConfig,
  pubsub: CommonsPubSubConfig,
  pubsubSubscription: String,
  scheduler: SchedulerConfig,
  api: ApiConfig
)

case class DatabaseConfig(
  url: String,
  maxPoolSize: Int
)

case class SchedulerConfig(
  maxThreads: Int,
  pollingIntervalSeconds: Int
)

case class ApiConfig(
  port: Int,
  username: Option[String],
  password: Option[String]
) {
  def isAuthEnabled: Boolean = username.isDefined && password.isDefined
}

object Config {
  def load(): AppConfig = {
    val config = ConfigFactory.load()
    
    // Load from environment variables with fallback to config file
    val databaseUrl = 
      sys.env.getOrElse(
        "DATABASE_URL",
        config.getString("database.url")
      )
    
    val pubsubProjectId = 
      sys.env.getOrElse(
        "PUBSUB_PROJECT_ID",
        config.getString("pubsub.project-id")
      )
    
    val pubsubSubscription = 
      sys.env.getOrElse(
        "PUBSUB_SUBSCRIPTION",
        config.getString("pubsub.subscription")
      )
    
    val pubsubCredentialsPath = 
      sys.env.get("PUBSUB_CREDENTIALS_PATH")
        .orElse(if (config.hasPath("pubsub.credentials-path")) {
          Some(config.getString("pubsub.credentials-path"))
        } else None)
    
    val maxThreads = 
      sys.env.get("MAX_THREADS")
        .map(_.toInt)
        .getOrElse(config.getInt("scheduler.max-threads"))
    
    val pollingInterval = 
      sys.env.get("POLLING_INTERVAL_SECONDS")
        .map(_.toInt)
        .getOrElse(config.getInt("scheduler.polling-interval-seconds"))
    
    val credentials = loadCredentials(pubsubCredentialsPath)
    
    val apiPort = 
      sys.env.get("API_PORT")
        .map(_.toInt)
        .getOrElse(config.getInt("api.port"))
    
    val apiUsername = 
      sys.env.get("API_USERNAME")
        .orElse(if (config.hasPath("api.username")) Some(config.getString("api.username")) else None)
        .filter(username => username.nonEmpty && username.length <= 255)
    
    val apiPassword = 
      sys.env.get("API_PASSWORD")
        .orElse(if (config.hasPath("api.password")) Some(config.getString("api.password")) else None)
        .filter(_.nonEmpty)
    
    AppConfig(
      database = DatabaseConfig(
        url = databaseUrl,
        maxPoolSize = config.getInt("database.max-pool-size")
      ),
      pubsub = CommonsPubSubConfig(
        project = pubsubProjectId,
        credentials = credentials
      ),
      pubsubSubscription = pubsubSubscription,
      scheduler = SchedulerConfig(
        maxThreads = maxThreads,
        pollingIntervalSeconds = pollingInterval
      ),
      api = ApiConfig(
        port = apiPort,
        username = apiUsername,
        password = apiPassword
      )
    )
  }
  
  private def loadCredentials(credentialsPath: Option[String]): GoogleCredentials = {
    credentialsPath match {
      case Some(path) =>
        ServiceAccountCredentials.fromStream(new FileInputStream(path))
      case None =>
        GoogleCredentials.getApplicationDefault
    }
  }
  
  def validate(config: AppConfig): Either[String, Unit] = {
    if (config.database.url.isEmpty) {
      Left("DATABASE_URL is required")
    } else if (config.pubsub.project.isEmpty) {
      Left("PUBSUB_PROJECT_ID is required")
    } else if (config.pubsubSubscription.isEmpty) {
      Left("PUBSUB_SUBSCRIPTION is required")
    } else {
      Right(())
    }
  }
}

