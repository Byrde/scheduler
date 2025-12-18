# Deployment Guide

This guide covers deploying the Pub/Sub Message Scheduler to Google Cloud Platform.

## Prerequisites

- Google Cloud Platform project with billing enabled
- `gcloud` CLI installed and authenticated
- Docker installed (for local builds)
- Access to GitHub Container Registry (`ghcr.io/byrde/scheduler`)

## Architecture Overview

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Pub/Sub Topic  │────▶│    Scheduler    │────▶│  Pub/Sub Topic  │
│  (inbound)      │     │    Service      │     │  (target)       │
└─────────────────┘     └────────┬────────┘     └─────────────────┘
                                 │
                        ┌────────▼────────┐
                        │    Cloud SQL    │
                        │   (PostgreSQL)  │
                        └─────────────────┘
```

## 1. Cloud SQL Database Provisioning

### Create a PostgreSQL Instance

```bash
# Set variables
PROJECT_ID="your-project-id"
INSTANCE_NAME="scheduler-db"
REGION="us-central1"

# Create Cloud SQL instance
gcloud sql instances create $INSTANCE_NAME \
  --database-version=POSTGRES_15 \
  --tier=db-f1-micro \
  --region=$REGION \
  --project=$PROJECT_ID

# Create database
gcloud sql databases create scheduler \
  --instance=$INSTANCE_NAME \
  --project=$PROJECT_ID

# Create user
gcloud sql users create scheduler \
  --instance=$INSTANCE_NAME \
  --password="YOUR_SECURE_PASSWORD" \
  --project=$PROJECT_ID
```

### Connection String Format

```
jdbc:postgresql:///<database>?cloudSqlInstance=<project>:<region>:<instance>&socketFactory=com.google.cloud.sql.postgres.SocketFactory
```

Example:
```
jdbc:postgresql:///scheduler?cloudSqlInstance=my-project:us-central1:scheduler-db&socketFactory=com.google.cloud.sql.postgres.SocketFactory
```

For direct IP connection (VPC or public IP):
```
jdbc:postgresql://<host>:5432/scheduler?user=scheduler&password=YOUR_PASSWORD
```

### Supported Databases

| Database | JDBC URL Format |
|----------|-----------------|
| PostgreSQL | `jdbc:postgresql://host:5432/database` |
| MySQL | `jdbc:mysql://host:3306/database` |
| SQL Server | `jdbc:sqlserver://host:1433;databaseName=database` |

The scheduler automatically detects the database type from the JDBC URL and initializes the appropriate db-scheduler schema.

## 2. Pub/Sub Setup

### Create Topics and Subscription

```bash
PROJECT_ID="your-project-id"

# Create inbound topic for schedule requests
gcloud pubsub topics create schedule-requests \
  --project=$PROJECT_ID

# Create subscription for the scheduler
gcloud pubsub subscriptions create schedule-requests-sub \
  --topic=schedule-requests \
  --ack-deadline=60 \
  --project=$PROJECT_ID

# Create a dead-letter topic for failed messages (optional but recommended)
gcloud pubsub topics create schedule-requests-dlq \
  --project=$PROJECT_ID

# Update subscription with dead-letter policy
gcloud pubsub subscriptions update schedule-requests-sub \
  --dead-letter-topic=schedule-requests-dlq \
  --max-delivery-attempts=5 \
  --project=$PROJECT_ID
```

### Create Target Topics

Create topics where scheduled messages will be published:

```bash
# Example target topics
gcloud pubsub topics create notifications --project=$PROJECT_ID
gcloud pubsub topics create reminders --project=$PROJECT_ID
gcloud pubsub topics create alerts --project=$PROJECT_ID
```

## 3. Service Account Permissions

### Create Service Account

```bash
PROJECT_ID="your-project-id"
SA_NAME="scheduler-service"

# Create service account
gcloud iam service-accounts create $SA_NAME \
  --display-name="Pub/Sub Message Scheduler" \
  --project=$PROJECT_ID

SA_EMAIL="$SA_NAME@$PROJECT_ID.iam.gserviceaccount.com"
```

### Required IAM Roles

| Role | Purpose |
|------|---------|
| `roles/pubsub.subscriber` | Subscribe to inbound schedule requests |
| `roles/pubsub.publisher` | Publish to target topics |
| `roles/cloudsql.client` | Connect to Cloud SQL |

```bash
# Grant Pub/Sub subscriber role
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/pubsub.subscriber"

# Grant Pub/Sub publisher role
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/pubsub.publisher"

# Grant Cloud SQL client role
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/cloudsql.client"
```

### Generate Service Account Key (for local development)

```bash
gcloud iam service-accounts keys create credentials.json \
  --iam-account=$SA_EMAIL \
  --project=$PROJECT_ID
```

> **Note:** For Cloud Run deployments, use the service account identity directly instead of key files.

## 4. Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DATABASE_URL` | Yes | - | JDBC connection string |
| `PUBSUB_PROJECT_ID` | Yes | - | Google Cloud project ID |
| `PUBSUB_SUBSCRIPTION` | Yes | - | Subscription name for inbound requests |
| `PUBSUB_CREDENTIALS_PATH` | No | ADC | Path to service account JSON file |
| `MAX_THREADS` | No | 10 | Worker threads for task execution |
| `POLLING_INTERVAL_SECONDS` | No | 10 | Database polling interval |
| `API_PORT` | No | 8081 | HTTP API server port |
| `API_USERNAME` | No | - | Basic auth username (enables auth when set with password) |
| `API_PASSWORD` | No | - | Basic auth password (enables auth when set with username) |

### Application Default Credentials (ADC)

When `PUBSUB_CREDENTIALS_PATH` is not set, the application uses Google Application Default Credentials:

1. **Cloud Run / GCE**: Automatically uses the attached service account
2. **Local development**: Set `GOOGLE_APPLICATION_CREDENTIALS` environment variable
3. **gcloud auth**: Falls back to `gcloud auth application-default login`

## 5. Docker Deployment

### Pull from GitHub Container Registry

```bash
docker pull ghcr.io/byrde/scheduler:latest
```

### Run with Docker

```bash
docker run -d \
  --name scheduler \
  -p 8080:8080 \
  -e DATABASE_URL="jdbc:postgresql://host:5432/scheduler?user=scheduler&password=PASSWORD" \
  -e PUBSUB_PROJECT_ID="your-project-id" \
  -e PUBSUB_SUBSCRIPTION="schedule-requests-sub" \
  -e PUBSUB_CREDENTIALS_PATH="/credentials/service-account.json" \
  -v /path/to/credentials.json:/credentials/service-account.json:ro \
  ghcr.io/byrde/scheduler:latest start
```

### Run with Docker Compose

```yaml
version: '3.8'
services:
  scheduler:
    image: ghcr.io/byrde/scheduler:latest
    ports:
      - "8080:8080"
    environment:
      DATABASE_URL: "jdbc:postgresql://db:5432/scheduler?user=scheduler&password=scheduler"
      PUBSUB_PROJECT_ID: "your-project-id"
      PUBSUB_SUBSCRIPTION: "schedule-requests-sub"
      PUBSUB_CREDENTIALS_PATH: "/credentials/service-account.json"
      MAX_THREADS: "20"
      POLLING_INTERVAL_SECONDS: "5"
    volumes:
      - ./credentials.json:/credentials/service-account.json:ro
    command: ["start"]
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

  db:
    image: postgres:15
    environment:
      POSTGRES_DB: scheduler
      POSTGRES_USER: scheduler
      POSTGRES_PASSWORD: scheduler
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  postgres_data:
```

### Build from Source

```bash
# Build Docker image locally
sbt Docker/publishLocal

# Run local build
docker run -d \
  --name scheduler \
  -p 8080:8080 \
  -e DATABASE_URL="..." \
  -e PUBSUB_PROJECT_ID="..." \
  -e PUBSUB_SUBSCRIPTION="..." \
  ghcr.io/byrde/scheduler:latest start
```

## 6. Cloud Run Deployment

### Deploy to Cloud Run

```bash
PROJECT_ID="your-project-id"
REGION="us-central1"
SA_EMAIL="scheduler-service@$PROJECT_ID.iam.gserviceaccount.com"
CLOUD_SQL_CONNECTION="$PROJECT_ID:$REGION:scheduler-db"

gcloud run deploy scheduler \
  --image=ghcr.io/byrde/scheduler:latest \
  --platform=managed \
  --region=$REGION \
  --service-account=$SA_EMAIL \
  --add-cloudsql-instances=$CLOUD_SQL_CONNECTION \
  --set-env-vars="DATABASE_URL=jdbc:postgresql:///scheduler?cloudSqlInstance=$CLOUD_SQL_CONNECTION&socketFactory=com.google.cloud.sql.postgres.SocketFactory,PUBSUB_PROJECT_ID=$PROJECT_ID,PUBSUB_SUBSCRIPTION=schedule-requests-sub" \
  --memory=512Mi \
  --cpu=1 \
  --min-instances=1 \
  --max-instances=3 \
  --port=8080 \
  --args="start" \
  --project=$PROJECT_ID
```

> **Note:** Cloud Run uses the service account identity directly; no credentials file needed.

## 7. Health Checks

### Endpoint

```
GET /health
```

### Healthy Response (200 OK)

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

### Unhealthy Response (503 Service Unavailable)

```json
{
  "status": "unhealthy",
  "checks": {
    "database": {
      "status": "unhealthy: Connection refused",
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

### Monitoring

```bash
# Simple health check
curl -f http://localhost:8080/health

# Health check with response details
curl -s http://localhost:8080/health | jq .
```

## 8. Troubleshooting

### Database Connection Issues

**Symptom:** Service fails to start or health check shows database unhealthy.

**Checks:**
1. Verify DATABASE_URL format matches your database type
2. Confirm network connectivity (VPC, firewall rules)
3. Verify credentials have correct permissions

```bash
# Test PostgreSQL connection
psql "jdbc:postgresql://host:5432/scheduler?user=scheduler"

# Check Cloud SQL connectivity
gcloud sql connect scheduler-db --user=scheduler
```

### Pub/Sub Connection Issues

**Symptom:** Service starts but doesn't receive messages, or health check shows pubsub unhealthy.

**Checks:**
1. Verify PUBSUB_PROJECT_ID matches the project containing your subscription
2. Verify PUBSUB_SUBSCRIPTION name is correct (not the full path)
3. Confirm service account has `roles/pubsub.subscriber` role

```bash
# Verify subscription exists
gcloud pubsub subscriptions describe schedule-requests-sub --project=$PROJECT_ID

# Test publish to inbound topic
gcloud pubsub topics publish schedule-requests \
  --message='{"test": true}' \
  --project=$PROJECT_ID
```

### Credential Issues

**Symptom:** Authentication errors in logs.

**Checks:**
1. If using credential file: verify PUBSUB_CREDENTIALS_PATH points to valid JSON
2. If using ADC on Cloud Run: verify service account is attached
3. For local dev: run `gcloud auth application-default login`

```bash
# Verify credentials file is valid JSON
cat /path/to/credentials.json | jq .

# Check ADC configuration
gcloud auth application-default print-access-token
```

### Task Execution Failures

**Symptom:** Messages are scheduled but not delivered to target topics.

**Checks:**
1. Verify service account has `roles/pubsub.publisher` on target topics
2. Check target topic exists
3. Review application logs for publish errors

```bash
# Verify topic exists
gcloud pubsub topics describe notifications --project=$PROJECT_ID

# Check IAM policy on topic
gcloud pubsub topics get-iam-policy notifications --project=$PROJECT_ID
```

### Memory or Performance Issues

**Symptom:** Service crashes or becomes unresponsive under load.

**Adjustments:**
1. Increase MAX_THREADS for higher concurrency
2. Decrease POLLING_INTERVAL_SECONDS for faster task pickup
3. Increase container memory allocation

```bash
# Cloud Run memory adjustment
gcloud run services update scheduler \
  --memory=1Gi \
  --set-env-vars="MAX_THREADS=20,POLLING_INTERVAL_SECONDS=5"
```

### Schema Initialization Failures

**Symptom:** Database connection succeeds but scheduler fails to start.

**Checks:**
1. Verify database user has CREATE TABLE permissions
2. Check if db-scheduler tables already exist with incompatible schema

```sql
-- Check for existing tables (PostgreSQL)
SELECT table_name FROM information_schema.tables 
WHERE table_schema = 'public' AND table_name LIKE 'scheduled%';

-- Drop and recreate if needed
DROP TABLE IF EXISTS scheduled_tasks;
```

## 9. Monitoring and Logging

### Structured Logs

The scheduler uses structured JSON logging. Key log fields:

| Field | Description |
|-------|-------------|
| `level` | Log level (INFO, WARN, ERROR) |
| `message` | Log message |
| `taskId` | Scheduled task identifier |
| `topic` | Target Pub/Sub topic |
| `executionTime` | Scheduled execution timestamp |

### Cloud Logging Queries

```
# All scheduler logs
resource.type="cloud_run_revision"
resource.labels.service_name="scheduler"

# Error logs only
resource.type="cloud_run_revision"
resource.labels.service_name="scheduler"
severity>=ERROR

# Task execution logs
resource.type="cloud_run_revision"
resource.labels.service_name="scheduler"
jsonPayload.taskId!=""
```

### Metrics to Monitor

1. **Health check success rate**: `/health` endpoint availability
2. **Task execution latency**: Time from scheduled time to actual execution
3. **Task failure rate**: Failed task executions / total executions
4. **Queue depth**: Pending tasks in database

## 10. Security Considerations

1. **Use Secret Manager** for database passwords instead of environment variables
2. **Enable VPC Service Controls** for Cloud SQL connections
3. **Restrict Pub/Sub IAM** to specific topics rather than project-wide
4. **Enable audit logging** for Cloud SQL and Pub/Sub operations
5. **Use private IP** for Cloud SQL in production environments
