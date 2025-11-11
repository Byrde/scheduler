# Domain-Driven Design (DDD) Strategy

### 1. Ubiquitous Language Glossary
| Term | Definition | Aliases |
| :--- | :--- | :--- |
| **Schedule Request** | An incoming Pub/Sub message requesting a future message delivery, containing execution time, target topic, and payload. | Inbound Message, Scheduling Request |
| **Scheduled Message** | A persisted task in the database representing a message awaiting execution at a specific time. | Task, Scheduled Task |
| **Execution Time** | The timestamp when a scheduled message should be published to its target topic. | Scheduled Time, Publish Time |
| **Target Topic** | The Google Cloud Pub/Sub topic where the scheduled message payload will be published. | Destination Topic, Outbound Topic |
| **Payload** | The message content to be published at the scheduled execution time. | Message Body, Message Data |
| **Task Execution** | The process of publishing a scheduled message's payload to its target topic when execution time is reached. | Message Publishing, Task Completion |

### 2. Core Domain and Bounded Context
* **Core Domain:** Message scheduling orchestration - the logic that transforms incoming schedule requests into persisted, reliable scheduled tasks and executes them at the correct time with proper failure handling.

* **Bounded Contexts:**
    * - **Scheduling Context:** Responsible for receiving schedule requests, validating scheduling parameters, and creating scheduled tasks. Uses the language of "requests," "execution time," and "task creation."
    * - **Execution Context:** Responsible for executing scheduled tasks by publishing payloads to target topics at the correct time. Uses the language of "task execution," "publishing," and "completion."
    * - **Persistence Context:** Responsible for database interactions and task storage using db-scheduler. Uses the language of "tasks," "persistence," and "polling."

### 3. Aggregates

* **ScheduleRequest Aggregate**
    * **Aggregate Root:** `ScheduleRequest`
    * **Entities:** None (single entity aggregate)
    * **Value Objects:** `ExecutionTime`, `TargetTopic`, `MessagePayload`
    * **Description:** Represents a validated request to schedule a message for future delivery. Enforces invariants such as execution time must be in the future, target topic must be valid, and payload must be non-empty. This aggregate is the entry point to the scheduling domain.

* **ScheduledMessage Aggregate**
    * **Aggregate Root:** `ScheduledMessage`
    * **Entities:** None (single entity aggregate)
    * **Value Objects:** `TaskId`, `ExecutionTime`, `TargetTopic`, `MessagePayload`, `TaskStatus`
    * **Description:** Represents a persisted scheduled message in the database. Enforces lifecycle rules: a task can only be executed once, execution results must be recorded, and failed executions can be retried. This aggregate bridges the domain model to db-scheduler's task representation.

