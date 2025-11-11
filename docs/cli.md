# CLI Documentation

This document describes the command-line interface for the Pub/Sub Message Scheduler.

## Overview

The scheduler provides a text-based CLI for testing, validation, and running the service. All commands are executed through the main JAR file.

## Usage

```bash
java -jar pubsub-message-scheduler.jar <command> [options]
```

## Commands

### `start`

Starts the scheduler service in production mode. This command:
- Loads and validates configuration
- Initializes database connection
- Starts the db-scheduler task processor
- Starts the Pub/Sub subscriber
- Starts the health check HTTP server
- Runs indefinitely until stopped (SIGTERM/SIGINT)

**Example:**
```bash
java -jar pubsub-message-scheduler.jar start
```

**Output:**
```
[INFO] Starting Pub/Sub Message Scheduler
[INFO] Database connection successful
[INFO] Health check server started on port 8080
[INFO] Starting message scheduler
[INFO] Listening to subscription: schedule-requests
[INFO] Service started successfully
```

**Environment Variables Required:**
- `DATABASE_URL`
- `PUBSUB_PROJECT_ID`
- `PUBSUB_SUBSCRIPTION`

---

### `test-config`

Validates the application configuration by loading all environment variables and checking required values.

**Example:**
```bash
java -jar pubsub-message-scheduler.jar test-config
```

**Output:**
```
Testing configuration...
✓ Configuration loaded successfully
  Database URL: jdbc:postgresql://localhost:5432/****
  Pub/Sub Project: my-gcp-project
  Pub/Sub Subscription: schedule-requests
  Credentials Path: /path/to/credentials.json
  Max Threads: 10
  Polling Interval: 10s
✓ Configuration is valid
```

**Exit Codes:**
- `0` - Configuration is valid
- `1` - Configuration is invalid or missing

---

### `test-db`

Tests the database connection by attempting to connect and execute a simple validation query.

**Example:**
```bash
java -jar pubsub-message-scheduler.jar test-db
```

**Output:**
```
Testing database connection...
✓ Database connection successful
```

**Exit Codes:**
- `0` - Database connection successful
- `1` - Database connection failed

**Notes:**
- Supports PostgreSQL, MySQL, and SQL Server
- The driver is auto-detected from the JDBC URL
- Connection pool is initialized but immediately closed after test

---

### `test-pubsub`

Tests the Pub/Sub client initialization and connection by verifying credentials and API access.

**Example:**
```bash
java -jar pubsub-message-scheduler.jar test-pubsub
```

**Output:**
```
Testing Pub/Sub connection...
✓ Pub/Sub client initialized successfully
  Project ID: my-gcp-project
✓ Pub/Sub connection test successful
```

**Exit Codes:**
- `0` - Pub/Sub connection successful
- `1` - Connection failed (bad credentials, network issues, insufficient permissions, etc.)

**Notes:**
- Verifies credentials by listing topics in the project
- Requires `pubsub.topics.list` permission on the project

---

### `schedule`

Interactive command to schedule a message for future delivery. This is useful for testing the end-to-end scheduling flow.

**Example:**
```bash
java -jar pubsub-message-scheduler.jar schedule
```

**Interactive Session:**
```
Interactive Message Scheduler
========================================
Target topic name: my-output-topic
Message payload (text): Hello, World!
Delay in seconds: 60
✓ Message scheduled successfully!
  Task ID: 550e8400-e29b-41d4-a716-446655440000
  Topic: my-output-topic
  Execution: Mon Nov 10 14:23:45 PST 2025
```

**Notes:**
- Starts a temporary scheduler instance
- Accepts plain text payloads (automatically encoded)
- Execution time calculated from delay in seconds
- Task is persisted to database before command exits

---

### `parse`

Parses and validates a schedule request JSON message without actually scheduling it.

**Example:**
```bash
java -jar pubsub-message-scheduler.jar parse '{"executionTime":1700000000000,"targetTopic":"test-topic","payload":{"data":"SGVsbG8=","attributes":{}}}'
```

**Output:**
```
Parsing schedule request...
✓ Valid schedule request
  Topic: test-topic
  Execution Time: 2023-11-14T22:13:20Z
  Payload Size: 5 bytes
  Attributes: 0
```

**Exit Codes:**
- `0` - Valid schedule request
- `1` - Invalid JSON or validation error

**Notes:**
- Can read from command-line argument or stdin
- Validates JSON structure, execution time, topic format, and payload encoding

---

### `help`

Displays usage information and command reference.

**Example:**
```bash
java -jar pubsub-message-scheduler.jar help
```

---

## Environment Variables

All commands respect these environment variables:

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DATABASE_URL` | Yes | - | JDBC connection string for PostgreSQL, MySQL, or SQL Server |
| `PUBSUB_PROJECT_ID` | Yes | - | Google Cloud project ID |
| `PUBSUB_SUBSCRIPTION` | Yes | - | Pub/Sub subscription name for inbound schedule requests |
| `PUBSUB_CREDENTIALS_PATH` | No | Default | Path to service account JSON file |
| `MAX_THREADS` | No | 10 | Number of worker threads for task execution |
| `POLLING_INTERVAL_SECONDS` | No | 10 | How often to poll database for due tasks |

## Exit Codes

- `0` - Success
- `1` - Error (configuration, connection, validation, etc.)

## Testing Workflow

Recommended workflow for testing a new deployment:

1. **Validate configuration**
   ```bash
   java -jar pubsub-message-scheduler.jar test-config
   ```

2. **Test database connection**
   ```bash
   java -jar pubsub-message-scheduler.jar test-db
   ```

3. **Test Pub/Sub credentials**
   ```bash
   java -jar pubsub-message-scheduler.jar test-pubsub
   ```

4. **Schedule a test message**
   ```bash
   java -jar pubsub-message-scheduler.jar schedule
   # Enter test values when prompted
   ```

5. **Start the service**
   ```bash
   java -jar pubsub-message-scheduler.jar start
   ```

6. **Monitor health endpoint**
   ```bash
   curl http://localhost:8080/health
   ```
   
   **Response when healthy:**
   ```json
   {
     "status": "healthy",
     "checks": {
       "database": {
         "status": "healthy",
         "responseTimeMs": 12
       },
       "pubsub": {
         "status": "healthy",
         "responseTimeMs": 45
       }
     },
     "totalResponseTimeMs": 57
   }
   ```
   
   **Response when unhealthy (returns 503):**
   ```json
   {
     "status": "unhealthy",
     "checks": {
       "database": {
         "status": "unhealthy: Database connection failed: Connection refused",
         "responseTimeMs": 5032
       },
       "pubsub": {
         "status": "healthy",
         "responseTimeMs": 48
       }
     },
     "totalResponseTimeMs": 5080
   }
   ```

## Examples

### Test Configuration in Docker

```bash
docker run --rm \
  -e DATABASE_URL="jdbc:postgresql://db:5432/scheduler" \
  -e PUBSUB_PROJECT_ID="my-project" \
  -e PUBSUB_SUBSCRIPTION="schedule-requests" \
  pubsub-message-scheduler:latest test-config
```

### Schedule a Message

```bash
# Prepare a valid JSON message
cat > request.json <<EOF
{
  "executionTime": $(($(date +%s) * 1000 + 300000)),
  "targetTopic": "notifications",
  "payload": {
    "data": "$(echo -n 'Reminder: Meeting in 5 minutes' | base64)",
    "attributes": {
      "priority": "high",
      "type": "reminder"
    }
  }
}
EOF

# Validate the JSON
java -jar pubsub-message-scheduler.jar parse "$(cat request.json)"

# Publish to inbound subscription
gcloud pubsub topics publish schedule-requests \
  --message "$(cat request.json)"
```

### Run Service with Custom Settings

```bash
export DATABASE_URL="jdbc:postgresql://localhost:5432/scheduler"
export PUBSUB_PROJECT_ID="my-project"
export PUBSUB_SUBSCRIPTION="schedule-requests"
export MAX_THREADS="20"
export POLLING_INTERVAL_SECONDS="5"

java -jar pubsub-message-scheduler.jar start
```

