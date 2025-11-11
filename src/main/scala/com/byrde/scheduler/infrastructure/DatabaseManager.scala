package com.byrde.scheduler.infrastructure

import org.byrde.logging.ScalaLogger
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.Connection
import javax.sql.DataSource

/**
 * Manages database connection pooling
 */
class DatabaseManager(config: DatabaseConfig) {
  
  private val logger = new ScalaLogger("DatabaseManager")
  
  private val dataSource: HikariDataSource = createDataSource()
  
  private def createDataSource(): HikariDataSource = {
    logger.logInfo(s"Initializing database connection pool")
    
    val hikariConfig = new HikariConfig()
    hikariConfig.setJdbcUrl(config.url)
    hikariConfig.setMaximumPoolSize(config.maxPoolSize)
    
    // Auto-detect driver based on JDBC URL
    val driver = detectDriver(config.url)
    hikariConfig.setDriverClassName(driver)
    
    // Connection pool settings
    hikariConfig.setConnectionTimeout(30000) // 30 seconds
    hikariConfig.setIdleTimeout(600000) // 10 minutes
    hikariConfig.setMaxLifetime(1800000) // 30 minutes
    
    new HikariDataSource(hikariConfig)
  }
  
  private def detectDriver(jdbcUrl: String): String = {
    jdbcUrl.toLowerCase match {
      case url if url.startsWith("jdbc:postgresql:") => "org.postgresql.Driver"
      case url if url.startsWith("jdbc:mysql:") => "com.mysql.cj.jdbc.Driver"
      case url if url.startsWith("jdbc:sqlserver:") => "com.microsoft.sqlserver.jdbc.SQLServerDriver"
      case _ => throw new IllegalArgumentException(s"Unsupported database type in URL: $jdbcUrl")
    }
  }
  
  /**
   * Gets the underlying DataSource
   */
  def getDataSource: DataSource = dataSource
  
  /**
   * Tests the database connection
   */
  def testConnection(): Either[String, Unit] = {
    try {
      val connection: Connection = dataSource.getConnection
      try {
        val valid = connection.isValid(5)
        if (valid) {
          logger.logInfo("Database connection test successful")
          Right(())
        } else {
          Left("Database connection is not valid")
        }
      } finally {
        connection.close()
      }
    } catch {
      case ex: Exception =>
        logger.logError(s"Database connection test failed: ${ex.getMessage}", ex)
        Left(s"Database connection failed: ${ex.getMessage}")
    }
  }
  
  /**
   * Closes the database connection pool
   */
  def close(): Unit = {
    logger.logInfo("Closing database connection pool")
    dataSource.close()
  }
}

