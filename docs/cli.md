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
- Starts the health check HTTP server (port 8080)
- Starts the HTTP API server with Swagger UI (port 8081)
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
[INFO] HTTP API server started on port 8081
[INFO] Starting message scheduler
[INFO] Listening to subscription: schedule-requests
[INFO] Service started successfully
[INFO] HTTP API available at http://localhost:8081/schedule
[INFO] Swagger UI available at http://localhost:8081/docs
```

**Environment Variables Required:**
- `DATABASE_URL`
- `PUBSUB_PROJECT_ID`
- `PUBSUB_SUBSCRIPTION`

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

### `openapi`

Generates OpenAPI specification for the HTTP API. Outputs YAML by default, or JSON with `--json` flag.

**Example (YAML):**
```bash
java -jar pubsub-message-scheduler.jar openapi > openapi.yaml
```

**Example (JSON):**
```bash
java -jar pubsub-message-scheduler.jar openapi --json > openapi.json
```

**Notes:**
- The generated spec is OpenAPI 3.1.0 compliant
- A pre-generated spec is available at `docs/openapi.yaml`

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
| `PUBSUB_CREDENTIALS_PATH` | No | ADC | Path to service account JSON file |
| `MAX_THREADS` | No | 10 | Number of worker threads for task execution |
| `POLLING_INTERVAL_SECONDS` | No | 10 | How often to poll database for due tasks |
| `API_PORT` | No | 8081 | HTTP API server port |
| `API_USERNAME` | No | - | Basic auth username (enables auth when set with password) |
| `API_PASSWORD` | No | - | Basic auth password (enables auth when set with username) |

## Exit Codes

- `0` - Success
- `1` - Error (configuration, connection, validation, etc.)

## Testing Workflow

Recommended workflow for testing a new deployment:

1. **Schedule a test message**
   ```bash
   java -jar pubsub-message-scheduler.jar schedule
   # Enter test values when prompted
   ```

2. **Start the service**
   ```bash
   java -jar pubsub-message-scheduler.jar start
   ```

3. **Monitor health endpoint**
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

### Schedule via HTTP API

The HTTP API provides a REST endpoint for scheduling messages. Swagger UI is available at `/docs`.

**Without authentication:**
```bash
curl -X POST http://localhost:8081/schedule \
  -H "Content-Type: application/json" \
  -d '{
    "schedule": {
      "type": "one-time",
      "executionTime": '"$(($(date +%s) * 1000 + 60000))"'
    },
    "targetTopic": "notifications",
    "payload": {
      "data": "SGVsbG8gV29ybGQ="
    }
  }'
```

**With Basic authentication:**
```bash
# Set credentials
export API_USERNAME="admin"
export API_PASSWORD="secret"

# Call API with Basic auth
curl -X POST http://localhost:8081/schedule \
  -H "Content-Type: application/json" \
  -u "$API_USERNAME:$API_PASSWORD" \
  -d '{
    "schedule": {
      "type": "one-time",
      "executionTime": '"$(($(date +%s) * 1000 + 60000))"'
    },
    "targetTopic": "notifications",
    "payload": {
      "data": "SGVsbG8gV29ybGQ="
    }
  }'
```

**Response (201 Created):**
```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "scheduled",
  "message": "Message scheduled successfully"
}
```

