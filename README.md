# Pub/Sub Message Scheduler

A portable Scala 2 service that receives Google Cloud Pub/Sub messages containing scheduling parameters and payloads, then publishes those payloads to specified topics at scheduled times using [db-scheduler](https://github.com/kagkarlsson/db-scheduler) for reliable, database-backed scheduling.

## Features

- **Multiple Schedule Types**: One-time, cron, fixed-delay, and daily schedules
- **Database-Backed Persistence**: Uses db-scheduler with PostgreSQL, MySQL, or SQL Server
- **Dynamic Topic Routing**: Each message specifies its destination topic
- **Cloud SQL Compatible**: Works with all Google Cloud SQL database types
- **Docker-Ready**: Single container deployment with minimal configuration
- **Health Monitoring**: Built-in health check endpoint
- **Comprehensive CLI**: Testing and validation tools

## Setup

### Prerequisites

- Java 11 or higher
- sbt 1.9.x
- Docker (for containerized deployment)
- Google Cloud Pub/Sub access
- Database (PostgreSQL, MySQL, or SQL Server)

### Local Development Setup

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd scheduler
   ```

2. **Install dependencies**
   ```bash
   sbt update
   ```

3. **Set up environment variables**
   ```bash
   export DATABASE_URL="jdbc:postgresql://localhost:5432/scheduler"
   export PUBSUB_PROJECT_ID="your-gcp-project"
   export PUBSUB_SUBSCRIPTION="schedule-requests"
   export PUBSUB_CREDENTIALS_PATH="/path/to/service-account.json"
   ```

4. **Initialize database**
   
   The db-scheduler library automatically creates the required schema on first run. Simply ensure your database exists and is accessible.

## Usage

### Build

```bash
# Compile
sbt compile

# Run tests
sbt test

# Build fat JAR
sbt assembly

# Build Docker image
sbt docker:publishLocal
```

### Run Locally

```bash
# Using sbt
sbt "run start"

# Using assembled JAR
java -jar target/scala-2.13/pubsub-message-scheduler-0.1.0.jar start
```

### CLI Commands

```bash
# Start the service
java -jar pubsub-message-scheduler.jar start

# Validate configuration
java -jar pubsub-message-scheduler.jar test-config

# Test database connection
java -jar pubsub-message-scheduler.jar test-db

# Test Pub/Sub connection
java -jar pubsub-message-scheduler.jar test-pubsub

# Schedule a message interactively
java -jar pubsub-message-scheduler.jar schedule

# Parse and validate JSON
java -jar pubsub-message-scheduler.jar parse '{"executionTime":...}'

# Show help
java -jar pubsub-message-scheduler.jar help
```

## Message Format

The scheduler supports multiple schedule types based on [db-scheduler](https://github.com/kagkarlsson/db-scheduler) capabilities.

### One-Time Execution

Schedule a message for delivery at a specific future time:

```json
{
  "schedule": {
    "type": "one-time",
    "executionTime": 1700000000000
  },
  "targetTopic": "notifications",
  "payload": {
    "data": "SGVsbG8sIFdvcmxkIQ==",
    "attributes": {"priority": "high"}
  }
}
```

### Cron Schedule

Execute messages on a cron schedule:

```json
{
  "schedule": {
    "type": "cron",
    "expression": "0 0 * * *",
    "initialExecutionTime": 1700000000000
  },
  "taskName": "daily-report",
  "targetTopic": "reports",
  "payload": {
    "data": "R2VuZXJhdGUgcmVwb3J0",
    "attributes": {"report_type": "daily"}
  }
}
```

**Common cron expressions:**
- `0 0 * * *` - Daily at midnight
- `0 */6 * * *` - Every 6 hours
- `0 30 9 * * *` - Every day at 9:30 AM
- `*/30 * * * *` - Every 30 minutes

### Fixed-Delay Schedule

Execute repeatedly with a fixed delay between executions:

```json
{
  "schedule": {
    "type": "fixed-delay",
    "delaySeconds": 3600
  },
  "taskName": "hourly-sync",
  "targetTopic": "sync-tasks",
  "payload": {
    "data": "U3luYyBkYXRh",
    "attributes": {"sync_type": "incremental"}
  }
}
```

### Daily Schedule

Execute daily at a specific time:

```json
{
  "schedule": {
    "type": "daily",
    "hour": 9,
    "minute": 30
  },
  "taskName": "morning-report",
  "targetTopic": "reports",
  "payload": {
    "data": "TW9ybmluZyByZXBvcnQ=",
    "attributes": {"period": "daily"}
  }
}
```

**Message Fields:**
- `schedule.type`: Schedule type (`one-time`, `cron`, `fixed-delay`, `daily`)
- `schedule.executionTime`: Unix timestamp in milliseconds (for one-time)
- `schedule.expression`: Cron expression (for cron)
- `schedule.delaySeconds`: Delay in seconds (for fixed-delay)
- `schedule.hour`/`minute`: Time of day (for daily, 0-23 hours, 0-59 minutes)
- `schedule.initialExecutionTime`: Optional initial execution time for recurring schedules
- `taskName`: **Recommended** for recurring tasks - unique identifier
- `targetTopic`: Topic name or full path (`projects/{project}/topics/{topic}`)
- `payload.data`: Base64-encoded message data
- `payload.attributes`: Optional key-value pairs

## Configuration

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DATABASE_URL` | Yes | - | JDBC connection string |
| `PUBSUB_PROJECT_ID` | Yes | - | GCP project ID |
| `PUBSUB_SUBSCRIPTION` | Yes | - | Subscription name for inbound messages |
| `PUBSUB_CREDENTIALS_PATH` | No | Default credentials | Path to service account JSON |
| `MAX_THREADS` | No | 10 | Scheduler thread pool size |
| `POLLING_INTERVAL_SECONDS` | No | 10 | Task polling interval |

### Database Connection Strings

```bash
# PostgreSQL
DATABASE_URL="jdbc:postgresql://localhost:5432/scheduler?user=admin&password=secret"

# MySQL
DATABASE_URL="jdbc:mysql://localhost:3306/scheduler?user=admin&password=secret"

# SQL Server
DATABASE_URL="jdbc:sqlserver://localhost:1433;databaseName=scheduler;user=admin;password=secret"
```

## CLI Commands

The scheduler provides a comprehensive CLI for testing and operations:

```bash
# Start the service
java -jar pubsub-message-scheduler.jar start

# Validate configuration
java -jar pubsub-message-scheduler.jar test-config

# Test database connection
java -jar pubsub-message-scheduler.jar test-db

# Test Pub/Sub connection
java -jar pubsub-message-scheduler.jar test-pubsub

# Schedule a message interactively
java -jar pubsub-message-scheduler.jar schedule

# Parse and validate JSON
java -jar pubsub-message-scheduler.jar parse '{"schedule":{...}}'

# Show help
java -jar pubsub-message-scheduler.jar help
```

## Publishing Messages

### Using gcloud CLI

```bash
# Create a schedule request message
cat > request.json <<EOF
{
  "schedule": {
    "type": "one-time",
    "executionTime": $(($(date +%s) * 1000 + 300000))
  },
  "targetTopic": "notifications",
  "payload": {
    "data": "$(echo -n 'Meeting in 5 minutes' | base64)",
    "attributes": {"type": "reminder"}
  }
}
EOF

# Publish to the scheduler's inbound subscription topic
gcloud pubsub topics publish schedule-requests \
  --message "$(cat request.json)"
```

## Deployment

### Docker Deployment

```bash
# Build image
docker build -t pubsub-message-scheduler:latest .

# Run container
docker run -d \
  -e DATABASE_URL="jdbc:postgresql://db:5432/scheduler" \
  -e PUBSUB_PROJECT_ID="my-project" \
  -e PUBSUB_SUBSCRIPTION="schedule-requests" \
  -e PUBSUB_CREDENTIALS_PATH="/app/creds/service-account.json" \
  -v /path/to/creds:/app/creds:ro \
  -p 8080:8080 \
  --name scheduler \
  pubsub-message-scheduler:latest start

# Check logs
docker logs -f scheduler

# Health check
curl http://localhost:8080/health
```

### Google Cloud Run

```bash
# Build and push image
gcloud builds submit --tag gcr.io/PROJECT_ID/pubsub-scheduler

# Deploy to Cloud Run
gcloud run deploy pubsub-scheduler \
  --image gcr.io/PROJECT_ID/pubsub-scheduler \
  --set-env-vars DATABASE_URL="jdbc:postgresql://..." \
  --set-env-vars PUBSUB_PROJECT_ID="PROJECT_ID" \
  --set-env-vars PUBSUB_SUBSCRIPTION="schedule-requests" \
  --platform managed \
  --region us-central1 \
  --allow-unauthenticated
```

## Infrastructure Setup

### 1. Create Cloud SQL Database

```bash
# Create PostgreSQL instance
gcloud sql instances create scheduler-db \
  --database-version=POSTGRES_15 \
  --tier=db-f1-micro \
  --region=us-central1

# Create database
gcloud sql databases create scheduler \
  --instance=scheduler-db

# Set password
gcloud sql users set-password postgres \
  --instance=scheduler-db \
  --password=YOUR_PASSWORD
```

### 2. Create Pub/Sub Resources

```bash
# Create inbound topic for schedule requests
gcloud pubsub topics create schedule-requests

# Create subscription for the scheduler service
gcloud pubsub subscriptions create schedule-requests-sub \
  --topic=schedule-requests \
  --ack-deadline=60

# Optional: Create dead-letter topic for failed messages
gcloud pubsub topics create schedule-requests-dlq

gcloud pubsub subscriptions update schedule-requests-sub \
  --dead-letter-topic=schedule-requests-dlq \
  --max-delivery-attempts=5

# Create output topics (examples)
gcloud pubsub topics create notifications
gcloud pubsub topics create reports
```

### 3. Create Service Account

```bash
# Create service account
gcloud iam service-accounts create pubsub-scheduler \
  --display-name="Pub/Sub Message Scheduler"

# Grant Pub/Sub permissions
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member="serviceAccount:pubsub-scheduler@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/pubsub.subscriber"

gcloud projects add-iam-policy-binding PROJECT_ID \
  --member="serviceAccount:pubsub-scheduler@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/pubsub.publisher"

# Grant Cloud SQL permissions
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member="serviceAccount:pubsub-scheduler@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/cloudsql.client"

# Download key
gcloud iam service-accounts keys create service-account.json \
  --iam-account=pubsub-scheduler@PROJECT_ID.iam.gserviceaccount.com
```