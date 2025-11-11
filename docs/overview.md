# Software Development Project: Pub/Sub Message Scheduler

## Overview

### 1. Project Name
* **Pub/Sub Message Scheduler**

### 2. Project Description
* A portable Scala 2 service that receives Google Cloud Pub/Sub messages containing scheduling parameters and payloads, then publishes those payloads to specified topics at scheduled times using db-scheduler for reliable, database-backed scheduling.

### 3. Project Vision
* Provide a reliable, easy-to-deploy message scheduling service that can be stood up in any environment with minimal configuration. The service should handle scheduled message delivery with persistence and fault tolerance, enabling asynchronous workflows and delayed message processing for distributed systems.

### 4. Problem Statement
* Distributed systems often require the ability to schedule messages for future delivery, such as delayed notifications, retry mechanisms, or time-based workflows. While Google Cloud Pub/Sub provides real-time message delivery, it lacks native scheduling capabilities. Existing solutions are either complex to deploy, lack persistence guarantees, or are tightly coupled to specific infrastructure.

### 5. Target Audience
* **Primary Audience:** Platform engineers and DevOps teams who need to deploy a reliable message scheduling service in Google Cloud environments with minimal operational overhead.
* **Secondary Audience:** Application developers building distributed systems that require delayed message delivery, scheduled workflows, or retry mechanisms with specific timing requirements.

### 6. Key Features
* - **Pub/Sub Message Ingestion:** Consume messages from a Google Cloud Pub/Sub subscription, where each message contains scheduling parameters (execution time) and the payload to be delivered later.
* - **Database-Backed Scheduling:** Use db-scheduler library for reliable, persistent scheduling with support for PostgreSQL, MySQL, and SQL Server (all Google Cloud SQL compatible databases).
* - **Dynamic Topic Routing:** Route scheduled messages to topics specified in the incoming message payload, enabling flexible multi-tenant or multi-workflow scenarios.
* - **Docker Deployment:** Provide a fully containerized deployment with minimal configuration requirements (database connection string and Pub/Sub credentials).
* - **Deployment Documentation:** Comprehensive documentation for standing up the service from scratch, including database provisioning, Pub/Sub setup, and Docker configuration.

### 7. Technology Stack
* **Language:** Scala 2.13
* **Build Tool:** sbt
* **Scheduling:** db-scheduler (Java library)
* **Message Queue:** Google Cloud Pub/Sub
* **Database:** PostgreSQL, MySQL, or SQL Server (Cloud SQL compatible, JDBC-based)
* **Deployment:** Docker
* **Testing:** ScalaTest

