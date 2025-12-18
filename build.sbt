import com.typesafe.sbt.packager.docker._

name := "pubsub-message-scheduler"

version := "0.1.0"

scalaVersion := "2.13.12"

organization := "com.byrde"

// GitHub Packages resolver
resolvers += "Byrde Commons" at "https://maven.pkg.github.com/Byrde/commons"

// Credentials for GitHub Packages (set via environment variables)
credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env.getOrElse("GITHUB_ACTOR", ""),
  sys.env.getOrElse("GITHUB_TOKEN", "")
)

libraryDependencies ++= Seq(
  // Byrde commons libraries
  "org.byrde" %% "logging" % "0.1.0",
  "org.byrde" %% "pubsub" % "0.1.0",
  
  // db-scheduler for persistent task scheduling
  "com.github.kagkarlsson" % "db-scheduler" % "16.0.0",
  
  // JDBC drivers for Cloud SQL support
  "org.postgresql" % "postgresql" % "42.7.1",
  "com.mysql" % "mysql-connector-j" % "8.2.0",
  "com.microsoft.sqlserver" % "mssql-jdbc" % "12.4.2.jre11",
  
  // Connection pooling
  "com.zaxxer" % "HikariCP" % "5.1.0",
  
  // Configuration
  "com.typesafe" % "config" % "1.4.3",
  
  // JSON parsing
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-generic" % "0.14.6",
  "io.circe" %% "circe-parser" % "0.14.6",
  
  // Testing
  "org.scalatest" %% "scalatest" % "3.2.17" % Test,
  "org.scalatestplus" %% "mockito-4-11" % "3.2.17.0" % Test
)

// Docker packaging
enablePlugins(JavaAppPackaging, DockerPlugin)

// Main class
Compile / mainClass := Some("com.byrde.scheduler.Main")

// Docker configuration
dockerBaseImage := "eclipse-temurin:11-jre-jammy"
dockerExposedPorts := Seq(8080)
dockerRepository := Some("ghcr.io/byrde")
dockerUpdateLatest := true

Docker / packageName := "scheduler"
Docker / daemonUser := "scheduler"
Docker / daemonUserUid := Some("1001")
Docker / daemonGroup := "scheduler"

// Add health check support
dockerCommands := dockerCommands.value.flatMap {
  case cmd @ Cmd("USER", _*) => Seq(
    Cmd("RUN", "apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*"),
    cmd
  )
  case ExecCmd("ENTRYPOINT", args @ _*) => Seq(
    Cmd("HEALTHCHECK", 
      "--interval=30s",
      "--timeout=10s", 
      "--start-period=60s",
      "--retries=3",
      "CMD", "curl -f http://localhost:8080/health || exit 1"
    ),
    ExecCmd("ENTRYPOINT", args: _*)
  )
  case other => Seq(other)
}

// Compiler options
scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard"
)

