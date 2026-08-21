# DevOps CI/CD Automation Platform

An enterprise-oriented CI/CD orchestration platform that parses pipeline definitions
from YAML, validates them through a multi-stage validation pipeline, persists
versioned configurations, and orchestrates asynchronous job execution via RabbitMQ
backed by an isolated worker execution model.

This platform separates a **control plane** (Spring Boot API that decides what
should happen) from a **data plane** (worker service that executes jobs). Every
significant action is persisted to PostgreSQL and tracked through an outbox event
system.

> **Not a replacement** for Jenkins, GitHub Actions, or GitLab CI. This is an
> educational, production-minded platform demonstrating how modern CI/CD systems
> are designed and integrated.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Key Features](#2-key-features)
3. [High-Level Architecture](#3-high-level-architecture)
4. [Detailed Component Architecture](#4-detailed-component-architecture)
5. [Pipeline YAML](#5-pipeline-yaml)
6. [YAML Processing Flow](#6-yaml-processing-flow)
7. [Pipeline Execution Flow](#7-pipeline-execution-flow)
8. [dependsOn Execution](#8-dependson-execution)
9. [RabbitMQ Flow](#9-rabbitmq-flow)
10. [Failure and Retry Handling](#10-failure-and-retry-handling)
11. [Database Model](#11-database-model)
12. [API Documentation](#12-api-documentation)
13. [Testing](#13-testing)
14. [Project Execution](#14-project-execution)
15. [Example End-to-End Execution](#15-example-end-to-end-execution)
16. [Error Handling](#16-error-handling)
17. [Observability](#17-observability)
18. [Security](#18-security)
19. [Current Project Status](#19-current-project-status)
20. [Known Limitations](#20-known-limitations)
21. [Future Enhancements](#21-future-enhancements)
22. [Interview Explanation](#22-interview-explanation)
23. [Architecture Diagram](#23-architecture-diagram)
24. [Final Repository Structure](#24-final-repository-structure)

---

## 1. Project Overview

### What problem does the platform solve?

Modern CI/CD pipelines are tightly coupled to specific platforms (GitHub Actions,
GitLab CI, Jenkins). Teams that operate across multiple repositories, providers,
and environments need a platform-agnostic orchestration layer that:

- Accepts pipeline definitions as YAML
- Validates them before execution (schema, semantics, dependency cycles)
- Persists immutable, versioned pipeline configurations
- Orchestrates asynchronous execution with retries, cancellation, and failure
  propagation
- Decouples the control plane from the execution plane via a message broker

### Why build this instead of relying only on a traditional CI/CD tool?

| Traditional CI/CD Tools | This Platform |
|---|---|
| Tightly coupled to a single SCM provider | Provider-agnostic (GitHub, GitLab, Bitbucket) |
| Pipeline logic mixed with platform-specific syntax | Pure YAML DSL with schema + semantic validation |
| Limited retry/cancellation at the pipeline level | First-class retry, cancellation, and failure propagation |
| No built-in separation of control/execution planes | Explicit control plane (API) / data plane (worker) boundary |
| No versioned pipeline history | Immutable pipeline versions stored in PostgreSQL |

### What does the platform currently do?

1. Accepts pipeline YAML via REST API, parses and validates it through schema,
   semantic, and dependency (cycle detection) validators
2. Persists pipeline definitions as immutable, versioned records
3. Creates pipeline runs on demand (API or webhook trigger)
4. Instantiates stages and jobs from the YAML configuration
5. Evaluates stage-level and job-level `dependsOn` dependencies at dispatch time
6. Dispatches eligible jobs to RabbitMQ as JSON messages
7. The control plane's `JobMessageConsumer` consumes results, updates job/stage/run
   statuses, and triggers dependent jobs
8. The separate worker service consumes job messages, clones repositories,
   executes build/test/scan/deploy commands, and publishes results
9. Retries transient failures up to a configurable maximum
10. Cancels running/pending jobs when a run is cancelled
11. Records every lifecycle event in an outbox table for reliable downstream
    publishing

---

## 2. Key Features

### Pipeline YAML Input
Users submit pipeline YAML via `POST /api/v1/pipelines/yaml` (to a project) or
`POST /api/v1/pipelines/{id}/versions` (to an existing pipeline). The YAML is
stored as-is in `PipelineVersion.yamlContent`.

### YAML Parsing
`PipelineYamlParser` uses SnakeYAML with a type-safe `PipelineRoot` →
`PipelineConfig` → `StageConfig` → `JobConfig` mapping. Empty or malformed YAML
is rejected with a `PipelineYamlParseException`.

### YAML Validation
Three validators run in sequence:
- **SchemaValidator**: pipeline name required, at least one stage, each stage
  needs a name and at least one job, each job needs a name and a valid type
  (`BUILD`, `TEST`, `SCAN`, `DEPLOY`, `PACKAGE`, `CUSTOM`)
- **SemanticValidator**: stage names unique across pipeline, job names unique
  per stage, all `dependsOn` references point to existing stages/jobs
- **DependencyValidator**: DFS-based cycle detection for both stage-level and
  job-level dependency graphs

### Pipeline Creation
`PipelineService.create()` creates a `Pipeline` entity linked to a `Project`.
Pipelines have statuses: `ACTIVE`, `INACTIVE`, `ARCHIVED`. Only `ACTIVE`
pipelines can be triggered.

### Pipeline Versions
Each YAML submission creates a new `PipelineVersion` with an auto-incrementing
version number. Versions are immutable once created. The YAML content is stored
in the `yaml_content` TEXT column.

### PipelineRun Creation
`RunService.triggerRun()` creates a `PipelineRun` with status `QUEUED`, then
immediately passes it to `PipelineOrchestrator.startExecution()`. Runs record
the pipeline version, repository, commit SHA, branch, trigger type, and
triggered-by identity.

### PipelineStage Creation
`PipelineOrchestrator.startExecution()` parses the YAML, maps it to
`StageDefinition` records via `PipelineConfigMapper`, and creates a
`PipelineStage` entity for each stage with `orderIndex` (0-based) and status
`PENDING`.

### PipelineJob Creation
For each `JobDefinition` within a stage, the orchestrator creates a `PipelineJob`
with the resolved `JobType` (defaults to `CUSTOM` if unrecognized) and status
`PENDING`.

### Stage-level dependsOn
Stages can declare `dependsOn` listing stage names they depend on. If no
`dependsOn` is declared, a stage requires all preceding stages (by orderIndex)
to be `SUCCESS`. The dispatcher checks dependency status before dispatching any
job within a stage.

### Job-level dependsOn
Within a stage, jobs can declare `dependsOn` listing sibling job names they
depend on. All declared dependencies must be `SUCCESS` before the dependent job
is dispatched.

### Pipeline Orchestration
`PipelineOrchestrator` is the central coordination point. After creating
stages/jobs and dispatching the first eligible batch, it receives job completion
callbacks, evaluates stage completion, evaluates run completion, handles retries,
and triggers the next batch of dependent jobs.

### Job Dispatching
`JobDispatcherService.dispatchReadyJobs()` iterates all stages, evaluates their
dependencies, finds eligible (PENDING) jobs within each eligible stage, and
dispatches them via `dispatchJob()`. Each dispatch creates a `JobAttempt`,
sets the job to `QUEUED`, and publishes a `JobDispatchMessage` to RabbitMQ.

### RabbitMQ Messaging
The control plane publishes to the `pipeline-jobs-exchange` with routing key
`job-dispatch`. The `JobMessageConsumer` listens on the `pipeline-jobs` queue
with manual ACK. The worker has its own separate topology (`cicd.jobs.exchange`,
`cicd.jobs`, `cicd.jobs.delay`, `cicd.jobs.dlq`).

### Worker Execution Flow
The worker's `PipelineJobConsumer` receives messages, validates the job, checks
for duplicates via `DuplicateJobGuard`, creates an isolated workspace (under a
temp directory organized by `runId/jobId`), clones the repository via JGit or
git CLI, executes the appropriate command based on job type, and publishes a
`PipelineResult`.

### Retry Handling
- **Application-level**: `PipelineOrchestrator.handleJobCompletion()` checks if
  `workspaceConfig.isRetryEnabled()` and the attempt count is below
  `workspaceConfig.getMaxRetries()`. If so, it calls
  `JobDispatcherService.dispatchForRetry()`, which creates a new `JobAttempt`
  and re-dispatches.
- **Worker-level**: `PipelineJobConsumer` catches `WorkerException`, checks the
  `x-retry-count` header against `worker.max-retries`, and routes to a delay
  queue for re-delivery.

### Failure Propagation
When a job fails:
1. Job status → `FAILED`
2. `StageResultCollector.evaluateStageStatus()` → if any job is FAILED, the
   stage is FAILED
3. `StageResultCollector.evaluateRunStatus()` → if any stage is FAILED, the
   run is FAILED
4. Failed stages block dependent stages from being dispatched
5. If retry is enabled and attempts remain, a retry is dispatched instead of
   propagating failure immediately

### Cancellation
`RunService.cancelRun()` → `PipelineOrchestrator.cancelRun()` sets the run to
`CANCELLED`, cancels all `PENDING`/`QUEUED` jobs and their attempts, and sets
`PENDING` stages to `SKIPPED`. Running jobs are NOT forcibly terminated at the
process level.

### Duplicate Message Handling
- **Control plane**: `JobMessageConsumer.onJobDispatch()` checks
  `job.getStatus() != QUEUED` and skips execution if already processed.
- **Worker**: `DuplicateJobGuard` uses `ConcurrentHashMap` with TTL-based
  eviction to prevent the same `jobId` from executing twice within a worker
  process.

### Outbox/Event Handling
`OutboxEventService` persists lifecycle events (`RUN_STARTED`, `JOB_COMPLETED`,
`STAGE_COMPLETED`, `RUN_COMPLETED`, `RUN_CANCELLED`) to the `outbox_events`
table within the same transaction as the state change. `OutboxEventPublisher`
runs on a `@Scheduled(fixedDelay=5000ms)` poll, publishes pending events to the
`outbox.exchange` RabbitMQ exchange, and marks them `PUBLISHED` or `FAILED`.

### API Endpoints
RESTful JSON API with controllers for Organizations, Projects, Repositories,
Pipelines, Runs, Artifacts, Deployments, Webhooks, and Health. All controllers
use Jakarta Bean Validation for request validation and a
`GlobalExceptionHandler` for consistent error responses.

---

## 3. High-Level Architecture

```
Client (API consumer / Webhook)
  │
  ▼
REST API (Spring Boot Control Plane)
  │
  ▼
Pipeline Controller
  │
  ▼
Pipeline YAML Service
  │
  ├──▶ YAML Parser (SnakeYAML → PipelineConfig)
  │
  ├──▶ Validation Pipeline
  │      ├── SchemaValidator
  │      ├── SemanticValidator
  │      └── DependencyValidator (cycle detection)
  │
  └──▶ PipelineConfigMapper → PipelineVersion (persisted)
  │
  ▼
Run API
  │
  ▼
RunService.triggerRun()
  │
  ▼
PipelineOrchestrator.startExecution()
  │
  ├──▶ PipelineRun (status → RUNNING)
  ├──▶ PipelineStage (created per YAML stage)
  ├──▶ PipelineJob (created per YAML job)
  │
  ▼
JobDispatcherService.dispatchReadyJobs()
  │
  ├──▶ Evaluates stage dependsOn
  ├──▶ Evaluates job dependsOn
  ├──▶ Creates JobAttempt
  └──▶ Publishes JobDispatchMessage
        │
        ▼
      RabbitMQ (pipeline-jobs-exchange → pipeline-jobs queue)
        │
        ▼
      JobMessageConsumer (control plane) ─── or ──▶ PipelineJobConsumer (worker)
        │                                              │
        │                                              ▼
        │                                        WorkerExecutor
        │                                          ├── Git clone
        │                                          ├── Build/Test/Scan/Deploy
        │                                          └── Publish result
        │
        ▼
      PipelineOrchestrator.handleJobCompletion()
        │
        ├──▶ Update job status (SUCCESS/FAILED)
        ├──▶ Update attempt status
        ├──▶ Evaluate stage completion
        ├──▶ If stage complete → update stage status
        ├──▶ If all stages complete → update run status
        ├──▶ If failed + retry enabled → dispatchForRetry()
        └──▶ dispatchReadyJobs() for dependent jobs
```

---

## 4. Detailed Component Architecture

### Controllers

| Controller | File | Responsibility | Key Methods |
|---|---|---|---|
| `HealthController` | `health/HealthController.java` | Health check for DB + RabbitMQ | `health()` |
| `OrganizationController` | `api/controller/OrganizationController.java` | CRUD for organizations | `create()`, `getById()`, `list()`, `update()`, `delete()` |
| `ProjectController` | `api/controller/ProjectController.java` | CRUD for projects within an org | `create()`, `getById()`, `list()`, `update()`, `delete()` |
| `RepositoryController` | `api/controller/RepositoryController.java` | CRUD for repos within a project | `create()`, `getById()`, `list()`, `update()`, `delete()`, `getRuns()` |
| `PipelineController` | `api/controller/PipelineController.java` | Pipeline CRUD + YAML submission + versions | `create()`, `getById()`, `list()`, `versions()`, `getVersion()`, `submitYaml()`, `submitYamlToProject()`, `update()`, `delete()`, `getRuns()` |
| `RunController` | `api/controller/RunController.java` | Run trigger + query + cancel | `triggerRun()`, `getRun()`, `listRuns()`, `cancelRun()`, `getStages()`, `getJobs()`, `getAttempts()` |
| `ArtifactController` | `api/controller/ArtifactController.java` | Artifact management per run | `create()`, `getById()`, `list()`, `delete()` |
| `DeploymentController` | `api/controller/DeploymentController.java` | Deployment tracking per run | `create()`, `getById()`, `list()`, `start()`, `complete()`, `delete()` |
| `WebhookController` | `api/controller/WebhookController.java` | Webhook ingestion (GitHub/GitLab) | `receiveWebhook()`, `getEvent()` |

### PipelineYamlService

- **File**: `pipeline/PipelineYamlService.java`
- **Class**: `PipelineYamlService`
- **Responsibility**: Orchestrates YAML submission: parse → validate → persist
  as a new `PipelineVersion`. Has two entry points: `submitYaml()` (for an
  existing pipeline) and `validateAndSubmitToProject()` (creates or finds
  the pipeline automatically).
- **Key methods**:
  - `submitYaml(UUID pipelineId, String yamlContent, String createdBy)`
  - `validateAndSubmitToProject(UUID projectId, String yamlContent, String createdBy)`
  - `parseYaml(String yamlContent)` → `PipelineConfig`
  - `validate(PipelineConfig)` → errors from all three validators

### PipelineYamlParser

- **File**: `pipeline/parser/PipelineYamlParser.java`
- **Class**: `PipelineYamlParser`
- **Responsibility**: Converts raw YAML string to `PipelineConfig` using
  SnakeYAML with `PipelineRoot` as the type-safe root.
- **Key methods**:
  - `parse(String yamlContent)` → `PipelineConfig`
  - `safeParseJson(String json)` → `Map<String, Object>` (static, used by
    `WebhookController`)

### SchemaValidator

- **File**: `pipeline/validator/SchemaValidator.java`
- **Class**: `SchemaValidator`
- **Responsibility**: Structural validation — pipeline name required, at least
  one stage, each stage needs a name and at least one job, each job needs a
  name and a valid type from `{BUILD, TEST, SCAN, DEPLOY, PACKAGE, CUSTOM}`.
- **Key methods**:
  - `validate(PipelineConfig config)` → `PipelineValidationResult`

### SemanticValidator

- **File**: `pipeline/validator/SemanticValidator.java`
- **Class**: `SemanticValidator`
- **Responsibility**: Semantic rules — unique stage names across pipeline,
  unique job names per stage, all stage `dependsOn` references exist, all
  job `dependsOn` references exist within the same stage.
- **Key methods**:
  - `validate(PipelineConfig config)` → `PipelineValidationResult`

### DependencyValidator

- **File**: `pipeline/validator/DependencyValidator.java`
- **Class**: `DependencyValidator`
- **Responsibility**: Cycle detection using DFS on both the stage-level and
  job-level dependency graphs.
- **Key methods**:
  - `validate(PipelineConfig config)` → `PipelineValidationResult`
  - `detectStageCycles(...)` — builds adjacency graph, runs `hasCycle()`
  - `detectJobCycles(...)` — per-stage, builds adjacency graph, runs `hasCycle()`
  - `dfs(...)` — standard depth-first cycle detection with visiting/visited sets

### PipelineConfigMapper

- **File**: `pipeline/PipelineConfigMapper.java`
- **Class**: `PipelineConfigMapper`
- **Responsibility**: Maps `PipelineConfig` → `List<StageDefinition>` with
  nested `List<JobDefinition>`, carrying `dependsOn` lists. Also maps
  `PipelineConfig` → `Pipeline` entity.
- **Key inner records**:
  - `StageDefinition(String name, int orderIndex, List<String> dependsOn, List<JobDefinition> jobs)`
  - `JobDefinition(String name, PipelineJob.JobType jobType, List<String> dependsOn)`
- **Key methods**:
  - `toStageDefinitions(PipelineConfig config)`
  - `toPipeline(PipelineConfig config, Project project)`
  - `resolveJobType(String type)` → `PipelineJob.JobType`

### RunService

- **File**: `execution/RunService.java`
- **Class**: `RunService`
- **Responsibility**: Creates and queries pipeline runs. Validates pipeline
  status before triggering. Delegates execution to `PipelineOrchestrator`.
  Records audit events.
- **Key methods**:
  - `triggerRun(UUID pipelineVersionId, String commitSha, String branch, UUID repositoryId, String triggeredBy)`
  - `getRun(UUID runId)`
  - `getRunsByVersion(UUID versionId)`
  - `getRunsByPipelineId(UUID pipelineId)`
  - `getRunsByRepositoryId(UUID repositoryId)`
  - `cancelRun(UUID runId)`
  - `getStages(UUID runId)`, `getJobs(UUID stageId)`, `getAttempts(UUID jobId)`

### PipelineOrchestrator

- **File**: `execution/PipelineOrchestrator.java`
- **Class**: `PipelineOrchestrator`
- **Responsibility**: Central coordination — creates stages and jobs from YAML,
  updates run to RUNNING, dispatches initial jobs, handles job completion
  callbacks (updates statuses, evaluates stage/run completion, triggers retries
  and dependent jobs), cancels runs.
- **Key methods**:
  - `startExecution(PipelineRun run)` — parses YAML, creates stages/jobs, sets
    run to RUNNING, dispatches ready jobs
  - `handleJobCompletion(UUID jobId, boolean success, int exitCode, String workerId, Instant startedAt, Instant finishedAt)` —
    updates job/attempt, evaluates stage, evaluates run, handles retry, dispatches
    next jobs
  - `cancelRun(UUID runId)` — sets run to CANCELLED, cancels pending/queued jobs,
    skips pending stages
  - `isStageComplete(PipelineStage)` — all jobs in terminal state
  - `isAllStagesComplete(PipelineRun)` — all stages in terminal state

### JobDispatcherService

- **File**: `execution/JobDispatcherService.java`
- **Class**: `JobDispatcherService`
- **Responsibility**: Evaluates dependencies and dispatches eligible jobs to
  RabbitMQ. Builds dependency maps from YAML content on each dispatch cycle.
- **Key methods**:
  - `dispatchReadyJobs(UUID runId)` — iterates stages, checks dependencies,
    dispatches eligible jobs
  - `dispatchJob(PipelineJob job)` — creates attempt, builds message, sends
    to RabbitMQ
  - `dispatchForRetry(PipelineJob job, int attemptNumber)` — creates new
    attempt, dispatches for retry
  - `areDeclaredDepsMet(List<String> declaredDeps, List<PipelineStage> stages)` —
    checks all declared stage dependencies are SUCCESS
  - `areJobDepsMet(List<String> declaredDeps, List<PipelineJob> jobs)` —
    checks all declared job dependencies are SUCCESS
  - `areAllPreviousStagesSuccess(PipelineStage, List<PipelineStage>)` —
    fallback for stages without explicit dependsOn
  - `buildDependencyMap(PipelineVersion)` — parses YAML, extracts stage deps
  - `buildJobDependencyMap(PipelineVersion)` — parses YAML, extracts job deps

### JobMessageConsumer (Control Plane)

- **File**: `execution/message/JobMessageConsumer.java`
- **Class**: `JobMessageConsumer`
- **Responsibility**: Consumes `JobDispatchMessage` from the `pipeline-jobs`
  queue. Looks up job/run, checks for cancellation, prevents duplicate
  execution, marks job RUNNING, updates attempt, builds `ExecutionContext`,
  calls `WorkerExecutor`, updates attempt on completion, notifies orchestrator.
- **Key methods**:
  - `onJobDispatch(JobDispatchMessage, Channel, long deliveryTag)` — main
    listener method with manual ACK/NACK

### WorkerExecutor

- **File**: `execution/worker/WorkerExecutor.java`
- **Class**: `WorkerExecutor`
- **Responsibility**: Executes a job within the control plane's embedded
  worker. Initializes workspace (git clone), builds the appropriate command
  based on job type, executes via `StepExecutor`, writes logs, returns success.
- **Key methods**:
  - `executeJob(ExecutionContext ctx)` → `boolean`
  - `buildCommand(ExecutionContext)` → `String` (auto-detects build tool)
  - `detectBuildCommand(Path)`, `detectTestCommand(Path)`, `detectScanCommand(Path)`,
    `detectDeployCommand(Path)`, `detectPackageCommand(Path)`, `detectCustomCommand(Path)`

### StageResultCollector

- **File**: `execution/StageResultCollector.java`
- **Class**: `StageResultCollector`
- **Responsibility**: Evaluates aggregate status of stages and runs based on
  child statuses.
- **Key methods**:
  - `evaluateStageStatus(PipelineStage, List<PipelineJob>)` → `StageStatus`
    - All SUCCESS → SUCCESS
    - Any FAILED → FAILED
    - All CANCELLED → FAILED
    - Otherwise → RUNNING
  - `evaluateRunStatus(List<PipelineStage>)` → `RunStatus`
    - All SUCCESS → SUCCESS
    - Any FAILED → FAILED
    - Otherwise → RUNNING

### Outbox/Event Components

| Class | File | Responsibility |
|---|---|---|
| `OutboxEventService` | `execution/OutboxEventService.java` | Persists outbox events within the same transaction as state changes |
| `OutboxEventPublisher` | `execution/OutboxEventPublisher.java` | Polls every 5 seconds, publishes PENDING events to RabbitMQ, marks PUBLISHED or FAILED |

- `OutboxEventService.publishEvent(eventType, aggregateType, aggregateId, payload)`
- `OutboxEventService.markPublished(eventId)`
- `OutboxEventService.markFailed(eventId, errorMessage)`
- `OutboxEventService.getPendingEvents()` → `List<OutboxEvent>`
- `OutboxEventPublisher.publishPendingEvents()` — `@Scheduled` poll

### Repository Layer

All repositories extend `JpaRepository<Entity, UUID>`:

| Repository | Entity | Notable Query Methods |
|---|---|---|
| `OrganizationRepository` | `Organization` | Standard CRUD |
| `ProjectRepository` | `Project` | `findByOrganizationId()` |
| `RepositoryRepository` | `Repository` | `findByProjectId()` |
| `PipelineRepository` | `Pipeline` | `findByProjectId()` |
| `PipelineVersionRepository` | `PipelineVersion` | `findByPipelineIdOrderByVersionDesc()`, `findLatestVersionIdsForRepository()` |
| `PipelineRunRepository` | `PipelineRun` | `findByPipelineVersionIdOrderByCreatedAtDesc()`, `findByRepositoryIdOrderByCreatedAtDesc()`, `findByPipelineIdOrderByCreatedAtDesc()` |
| `PipelineStageRepository` | `PipelineStage` | `findByPipelineRunIdOrderByOrderIndexAsc()` |
| `PipelineJobRepository` | `PipelineJob` | `findByPipelineStageId()` |
| `JobAttemptRepository` | `JobAttempt` | `findByJobIdOrderByAttemptNumberAsc()` |
| `OutboxEventRepository` | `OutboxEvent` | `findByStatusOrderByCreatedAtAsc()` |
| `WebhookEventRepository` | `WebhookEvent` | `existsByProviderAndDeliveryId()`, `findByProviderAndDeliveryId()` |
| `AuditEventRepository` | `AuditEvent` | Standard CRUD |
| `ArtifactRepository` | `Artifact` | `findByPipelineRunId()` |
| `DeploymentRepository` | `Deployment` | `findByPipelineRunId()`, `findByEnvironment()` |

### Database

PostgreSQL (14 tables) managed by Flyway (`V1__create_domain_schema.sql`).
Connection pooling via HikariCP. Schema validation mode (`ddl-auto: validate`).

### RabbitMQ

Two separate topologies:

**Control plane** (declared in `execution/config/RabbitMQConfig.java`):
- `pipeline-jobs-exchange` (direct) → `pipeline-jobs` queue (routing key: `job-dispatch`)
- `pipeline-job-results-exchange` (direct) → `pipeline-job-results` queue
- `outbox.exchange` (direct) → `outbox.queue`
- Manual ACK, Jackson2JsonMessageConverter

**Worker** (declared in `worker/config/RabbitMQConfig.java`):
- `cicd.jobs.exchange` (direct) → `cicd.jobs` (main), `cicd.jobs.delay` (TTL retry), `cicd.jobs.dlq` (dead letter)
- `cicd.results.exchange` (direct)
- Manual ACK, Jackson2JsonMessageConverter

---

## 5. Pipeline YAML

### Example

```yaml
pipeline:
  name: build-and-deploy
  description: Build, test, scan, and deploy the application

  stages:
    - name: build
      jobs:
        - name: compile
          type: BUILD

    - name: quality
      dependsOn:
        - build
      jobs:
        - name: unit-tests
          type: TEST
        - name: code-scan
          type: SCAN
          dependsOn:
            - unit-tests

    - name: deploy-staging
      dependsOn:
        - quality
      jobs:
        - name: deploy-to-staging
          type: DEPLOY
```

### Structure

```
pipeline
  ├── name              (string, required, max 255 chars)
  ├── description       (string, optional)
  └── stages[]          (list, required, at least one)
       ├── name         (string, required, unique across pipeline, max 255 chars)
       ├── dependsOn    (list of strings, optional)
       └── jobs[]       (list, required, at least one)
            ├── name    (string, required, unique within stage, max 255 chars)
            ├── type    (string, required: BUILD|TEST|SCAN|DEPLOY|PACKAGE|CUSTOM)
            └── dependsOn  (list of strings, optional)
```

### Stage-level dependsOn

Declares which stages must complete successfully before this stage's jobs can
run. Without explicit `dependsOn`, all stages with a lower `orderIndex` must
be `SUCCESS` (sequential ordering). Example:

```yaml
stages:
  - name: build
    jobs:
      - name: compile
        type: BUILD

  - name: deploy
    dependsOn:
      - build     # deploy only runs after build succeeds
    jobs:
      - name: push
        type: DEPLOY
```

### Job-level dependsOn

Declares which sibling jobs **within the same stage** must complete successfully
before this job can run. Example:

```yaml
  - name: quality
    jobs:
      - name: unit-tests
        type: TEST
      - name: integration-tests
        type: TEST
        dependsOn:
          - unit-tests    # only runs after unit-tests succeeds
```

### Key Difference

| | Stage dependsOn | Job dependsOn |
|---|---|---|
| Scope | Cross-stage | Within same stage |
| References | Stage names | Job names (same stage) |
| Default behavior | All previous stages must be SUCCESS | No dependency (runs immediately) |
| Checked by | `JobDispatcherService.areDeclaredDepsMet()` | `JobDispatcherService.areJobDepsMet()` |

---

## 6. YAML Processing Flow

```
YAML Request (raw string)
  │
  ▼
PipelineYamlParser.parse(yamlContent)
  │  Uses SnakeYAML → PipelineRoot → PipelineConfig
  │  Throws PipelineYamlParseException on invalid YAML
  ▼
PipelineConfig (POJO: name, description, stages[], jobs[], dependsOn)
  │
  ▼
SchemaValidator.validate(config)
  │  Checks: name required, stages required, jobs required,
  │  job type valid → PipelineValidationResult
  ▼
SemanticValidator.validate(config)  [only if schema valid]
  │  Checks: unique stage names, unique job names per stage,
  │  stage dependsOn references exist, job dependsOn references exist
  ▼
DependencyValidator.validate(config)  [only if schema valid]
  │  Checks: no cycles in stage dependency graph,
  │  no cycles in job dependency graphs per stage
  ▼
PipelineValidationResult
  │  If errors → throw PipelineValidationException (HTTP 422)
  ▼
PipelineConfigMapper.toStageDefinitions(config)
  │  Produces List<StageDefinition>
  │  Each StageDefinition carries:
  │    - name, orderIndex, dependsOn (from YAML), List<JobDefinition>
  │    Each JobDefinition carries: name, jobType, dependsOn (from YAML)
  ▼
PipelineVersion (persisted)
  │  - version number (auto-incremented)
  │  - yamlContent (original YAML string)
  │  - pipeline reference, createdBy, commitSha
  ▼
Dependencies are carried in:
  - StageDefinition.dependsOn → used by JobDispatcherService.buildDependencyMap()
  - JobDefinition.dependsOn → used by JobDispatcherService.buildJobDependencyMap()
  Both maps are rebuilt from YAML on each dispatch cycle.
```

---

## 7. Pipeline Execution Flow

When `POST /api/v1/runs` is called:

```
1. RunController.triggerRun(request)
   │  Extracts: pipelineVersionId, commitSha, branch, repositoryId, triggeredBy
   ▼
2. RunService.triggerRun(...)
   │  a. Load PipelineVersion → validate exists
   │  b. Load Pipeline → validate status == ACTIVE
   │  c. Load Repository (if provided)
   │  d. Create PipelineRun (status=QUEUED, triggerType=API)
   │  e. Save PipelineRun
   │  f. AuditService.record("TRIGGER_RUN", ...)
   ▼
3. PipelineOrchestrator.startExecution(run)
   │  a. Load PipelineVersion, parse YAML → PipelineConfig
   │  b. Map to StageDefinitions via PipelineConfigMapper
   │  c. For each StageDefinition:
   │       Create PipelineStage (status=PENDING, orderIndex=i)
   │       Save PipelineStage → log [STAGE_CREATED]
   │       For each JobDefinition:
   │         Create PipelineJob (status=PENDING, jobType resolved)
   │         Save PipelineJob
   │  d. Set run status → RUNNING, startedAt = now()
   │  e. Save PipelineRun
   │  f. OutboxEventService.publishEvent("RUN_STARTED", ...)
   ▼
4. JobDispatcherService.dispatchReadyJobs(runId)
   │  a. Load run → verify status == RUNNING
   │  b. Load all stages (ordered by orderIndex)
   │  c. Build dependency maps from YAML:
   │       stageDependencies: Map<stageName, List<depStageName>>
   │       jobDependencies: Map<stageName, Map<jobName, List<depJobName>>>
   │  d. For each stage (not SUCCESS/SKIPPED):
   │       Check stage dependencies (explicit or positional)
   │       If deps met → for each PENDING job:
   │         Check job dependencies within stage
   │         If job deps met → dispatchJob(job)
   ▼
5. JobDispatcherService.dispatchJob(job)
   │  a. Set job status → QUEUED, save
   │  b. Create JobAttempt (attemptNumber computed, status=PENDING), save
   │  c. Build JobDispatchMessage with all context
   │  d. RabbitTemplate.convertAndSend("pipeline-jobs-exchange", "job-dispatch", message)
   │  e. Log [JOB_DISPATCHED]
```

**Status flow at each step:**

| Step | Entity | Status Change |
|---|---|---|
| Run created | `PipelineRun` | → QUEUED |
| Orchestrator starts | `PipelineRun` | QUEUED → RUNNING |
| Stages created | `PipelineStage` | → PENDING |
| Jobs created | `PipelineJob` | → PENDING |
| Job dispatched | `PipelineJob` | PENDING → QUEUED |
| Consumer receives | `PipelineJob` | QUEUED → RUNNING |
| Job completes | `PipelineJob` | RUNNING → SUCCESS or FAILED |
| Stage completes | `PipelineStage` | RUNNING → SUCCESS/FAILED/SKIPPED |
| All stages complete | `PipelineRun` | RUNNING → SUCCESS/FAILED |

---

## 8. dependsOn Execution

### Stage-level dependsOn

**How dependencies are obtained:**
`JobDispatcherService.buildDependencyMap(PipelineVersion)` parses the YAML via
`PipelineYamlParser` and `PipelineConfigMapper.toStageDefinitions()`, then
builds a `Map<String, List<String>>` mapping each lowercase stage name to its
declared dependency names.

**How they are checked:**
`areDeclaredDepsMet(List<String> declaredDeps, List<PipelineStage> stages)`:
For each declared dependency name, finds the matching stage (case-insensitive)
and checks that its status is `SUCCESS`. All must be SUCCESS for the result
to be true.

**Fallback (no explicit dependsOn):**
`areAllPreviousStagesSuccess(stage, stages)`: All stages with a lower
`orderIndex` must be `SUCCESS`.

**What happens if a dependency is:**

| Dependency Status | Result |
|---|---|
| PENDING | Stage NOT dispatched (waiting) |
| RUNNING | Stage NOT dispatched (waiting) |
| FAILED | Stage NOT dispatched (blocked) |
| SUCCESS | Dependency met, one step closer to dispatch |
| SKIPPED | Treated as not SUCCESS → NOT dispatched |
| Missing (not found) | `areDeclaredDepsMet` returns false → NOT dispatched |

### Job-level dependsOn

**How dependencies are obtained:**
`JobDispatcherService.buildJobDependencyMap(PipelineVersion)` produces a
`Map<String, Map<String, List<String>>>` mapping stage name → job name → list
of dependency job names.

**How they are checked:**
`areJobDepsMet(List<String> declaredDeps, List<PipelineJob> jobs)`: For each
declared dependency name, finds the matching job in the same stage
(case-insensitive) and checks status == `SUCCESS`. All must be SUCCESS.

**Same status behavior as stage-level dependencies** — only `SUCCESS` satisfies
a dependency.

### Multiple Dependencies

All dependencies must be satisfied. If a job has `[unit-tests, code-scan]` as
`dependsOn`, both `unit-tests` AND `code-scan` must be `SUCCESS` before the
job is dispatched.

---

## 9. RabbitMQ Flow

### Job Dispatch (Control Plane → Queue)

```
JobDispatcherService.sendDispatchMessage(message)
  │
  ▼
RabbitTemplate.convertAndSend(
    "pipeline-jobs-exchange",     ← exchange
    "job-dispatch",               ← routing key
    JobDispatchMessage            ← JSON payload
)
  │
  ▼
RabbitMQ broker routes to "pipeline-jobs" queue (bound to exchange with routing key "job-dispatch")
```

### Job Consumption (Control Plane embedded worker)

```
JobMessageConsumer.onJobDispatch(message, channel, deliveryTag)
  │
  ├── 1. Find PipelineJob by jobId
  ├── 2. Find PipelineRun by runId
  ├── 3. Check if run is CANCELLED → ACK and return
  ├── 4. Check if job status != QUEUED → ACK (duplicate prevention)
  ├── 5. Set job status → RUNNING
  ├── 6. Find current JobAttempt → set to RUNNING
  ├── 7. Create workspace (work/, logs/, artifacts/)
  ├── 8. Build ExecutionContext
  ├── 9. WorkerExecutor.executeJob(context) → boolean
  ├── 10. Update JobAttempt status
  ├── 11. PipelineOrchestrator.handleJobCompletion(...)
  └── 12. channel.basicAck(deliveryTag, false)
          │
          On exception:
          ├── Update JobAttempt to FAILED
          └── channel.basicNack(deliveryTag, false, false)  ← no requeue
```

### Worker Consumption (Separate service)

```
PipelineJobConsumer.onMessage(message, channel)
  │
  ├── Deserialize PipelineJob from JSON
  ├── Validate via PipelineJobValidator
  │     → Invalid: channel.basicReject(deliveryTag, false)
  ├── DuplicateJobGuard.tryAcquire(jobId)
  │     → Duplicate: channel.basicAck(deliveryTag, false)
  ├── PipelineExecutionService.execute(job) → PipelineResult
  ├── PipelineResultPublisher.publish(result)
  └── channel.basicAck(deliveryTag, false)
      │
      On WorkerException:
      ├── Check x-retry-count < worker.max-retries
      │   → Retry: publish to delay queue, ACK
      │   → Exhausted: publish failure result, reject to DLQ
```

### Worker RabbitMQ Topology

```
cicd.jobs.exchange (direct)
  ├── cicd.jobs          ← main queue    (rk: cicd.job.submitted)
  ├── cicd.jobs.delay    ← TTL queue     (rk: cicd.job.delay)
  └── cicd.jobs.dlq      ← dead letter   (rk: cicd.job.dead)

cicd.results.exchange (direct) → result routing key
```

Retry flow: Transient failure → published to delay queue with `x-retry-count`
header → after `x-message-ttl` expires, dead-lettered back to main jobs queue.

### Acknowledgement/NACK Behavior

| Scenario | Control Plane | Worker |
|---|---|---|
| Job executed successfully | `basicAck` | `basicAck` |
| Job cancelled (control plane) | `basicAck` (skip) | N/A |
| Job duplicate (not QUEUED) | `basicAck` (skip) | `basicAck` (guard) |
| Malformed message | N/A | `basicReject(requeue=false)` |
| Invalid job | N/A | `basicReject(requeue=false)` |
| Infrastructure failure, retries left | N/A | `basicAck` → publish to delay queue |
| Infrastructure failure, retries exhausted | N/A | `basicReject(requeue=false)` → DLQ |
| Unexpected exception | `basicNack(requeue=false)` | N/A |

---

## 10. Failure and Retry Handling

### Job Failure

When a job completes with `success=false`:

1. `PipelineOrchestrator.handleJobCompletion()` sets `PipelineJob` to `FAILED`
2. Updates the latest `JobAttempt` to `FAILED`
3. Publishes `JOB_COMPLETED` outbox event
4. If retry is enabled and attempts < maxRetries:
   - Calls `JobDispatcherService.dispatchForRetry()` which creates a new
     `JobAttempt` and re-dispatches
   - Returns early (does NOT propagate failure to stage yet)
5. If retry is exhausted or disabled:
   - Calls `StageResultCollector.evaluateStageStatus()` → stage becomes `FAILED`
   - Calls `StageResultCollector.evaluateRunStatus()` → run becomes `FAILED`
   - Dispatches dependent jobs only if run is still RUNNING

### Stage Failure

`StageResultCollector.evaluateStageStatus()`:
- Any job FAILED → stage FAILED
- All jobs cancelled → stage FAILED
- All jobs SUCCESS → stage SUCCESS
- Otherwise → stage RUNNING

Failed stages block all stages that `dependsOn` them.

### Pipeline/Run Failure

`StageResultCollector.evaluateRunStatus()`:
- Any stage FAILED → run FAILED
- All stages SUCCESS → run SUCCESS
- Otherwise → run RUNNING

### Retry (Application-Level)

- **Location**: `PipelineOrchestrator.handleJobCompletion()` (lines 222-233)
- **Config**: `WorkspaceConfig.retryEnabled` (default: true),
  `WorkspaceConfig.maxRetries` (default: 3)
- **Mechanism**: Creates new `JobAttempt` with incremented `attemptNumber`,
  sets job to `QUEUED`, dispatches via `dispatchForRetry()`
- **Limitation**: Does NOT reset stage/run status on retry — stage stays
  in whatever status it was in while the retry runs

### Retry (Worker-Level)

- **Location**: `PipelineJobConsumer.handleInfrastructureFailure()`
- **Config**: `worker.retry-enabled`, `worker.max-retries` (default: 3),
  `worker.retry-delay-ms` (default: 30000)
- **Mechanism**: Publishes to `cicd.jobs.delay` queue with incremented
  `x-retry-count`. After TTL expires, dead-letters back to main queue.
- **Deduplication**: `DuplicateJobGuard.markFailed()` removes from in-flight
  before retry, allowing re-acquisition

### JobAttempt

Each dispatch creates a `JobAttempt` entity tracking:
- `attemptNumber` (1, 2, 3, ...)
- `status` (PENDING → RUNNING → SUCCESS/FAILED/CANCELLED)
- `exitCode`, `startedAt`, `finishedAt`, `logsLocation`

Unique constraint: `(job_id, attempt_number)`.

### Cancellation

`RunService.cancelRun(runId)`:
1. Validates run is not already in a terminal state
2. Delegates to `PipelineOrchestrator.cancelRun(runId)`
3. Orchestrator sets run to `CANCELLED`, iterates all stages:
   - For each job with PENDING or QUEUED status → sets to CANCELLED
   - For each PENDING/RUNNING attempt → sets to CANCELLED
   - For each stage with PENDING status → sets to SKIPPED
4. Publishes `RUN_CANCELLED` outbox event

**Note**: RUNNING jobs are NOT forcibly terminated. The consumer checks
`run.getStatus() == CANCELLED` on receipt and skips execution.

### Duplicate Messages

- **Control Plane**: `JobMessageConsumer` checks `job.getStatus() != QUEUED`
  and ACKs without execution
- **Worker**: `DuplicateJobGuard` uses `ConcurrentHashMap.putIfAbsent()` with
  10-minute TTL eviction

---

## 11. Database Model

### Entities

| Entity | Table | Purpose | Key Fields | Relationships |
|---|---|---|---|---|
| `Organization` | `organizations` | Top-level tenant | id, name, slug (unique), status | has many Projects |
| `Project` | `projects` | Project within an org | id, organization_id, name, slug, status | belongs to Organization, has many Pipelines/Repos |
| `Repository` | `repositories` | Git repository | id, project_id, provider (GITHUB/GITLAB/BITBUCKET), repository_url, repository_name, default_branch, webhook_id, status | belongs to Project |
| `Pipeline` | `pipeline` | Pipeline definition | id, project_id, name, description, status (ACTIVE/INACTIVE/ARCHIVED) | belongs to Project, has many Versions |
| `PipelineVersion` | `pipeline_versions` | Immutable YAML version | id, pipeline_id, version (auto-increment), yaml_content (TEXT), commit_sha, created_by | belongs to Pipeline, has many Runs |
| `PipelineRun` | `pipeline_runs` | Execution instance | id, pipeline_version_id, repository_id, commit_sha, branch, trigger_type, triggered_by, status, started_at, finished_at | belongs to PipelineVersion, has many Stages |
| `PipelineStage` | `pipeline_stages` | Stage within a run | id, pipeline_run_id, name, order_index, status, started_at, finished_at | belongs to PipelineRun, has many Jobs |
| `PipelineJob` | `pipeline_jobs` | Job within a stage | id, pipeline_stage_id, name, job_type, status, worker_id, started_at, finished_at, exit_code | belongs to PipelineStage, has many Attempts |
| `JobAttempt` | `job_attempts` | Execution attempt | id, job_id, attempt_number, status, exit_code, started_at, finished_at, logs_location | belongs to PipelineJob |
| `WebhookEvent` | `webhook_events` | Webhook delivery | id, provider, delivery_id, event_type, repository_id, payload (JSONB), received_at, status | belongs to Repository |
| `OutboxEvent` | `outbox_events` | Reliable event log | id, event_type, aggregate_type, aggregate_id, payload (JSONB), status, created_at, published_at | independent |
| `Artifact` | `artifacts` | Build artifacts | id, pipeline_run_id, job_id, artifact_type, name, location_url, image_digest | belongs to PipelineRun, optionally PipelineJob |
| `Deployment` | `deployments` | Deployments | id, pipeline_run_id, environment, image_digest, status, endpoint | belongs to PipelineRun |
| `AuditEvent` | `audit_events` | Audit trail | id, actor, action, resource_type, resource_id, metadata (JSONB), correlation_id | independent |

### ER Relationship Diagram

```
Organization (1) ──────< (N) Project
                                │
                    ┌───────────┴───────────┐
                    ▼                       ▼
              Repository (N)          Pipeline (N)
              belongs to Project       belongs to Project
                    │                       │
                    │                 PipelineVersion (N)
                    │                 belongs to Pipeline
                    │                       │
                    │                 PipelineRun (N)
                    │                 belongs to PipelineVersion
                    │                       │
                    │           ┌───────────┴───────────┐
                    │           ▼                       ▼
                    │     PipelineStage (N)      Artifact (N)
                    │     belongs to PipelineRun  belongs to PipelineRun
                    │           │
                    │           ▼
                    │     PipelineJob (N)
                    │     belongs to PipelineStage
                    │           │
                    │           ▼
                    │     JobAttempt (N)
                    │     belongs to PipelineJob
                    │
                    └──> WebhookEvent (N)
                         belongs to Repository (optional)

OutboxEvent ──── independent (created during state transitions)
AuditEvent ───── independent (created during trigger/cancel)
Deployment ───── belongs to PipelineRun
```

---

## 12. API Documentation

All endpoints are under `/api/v1`. Request/response bodies are JSON.

### Health

| Method | Path | Purpose | Response |
|---|---|---|---|
| `GET` | `/api/v1/health` | System health check (DB + RabbitMQ) | `HealthResponse { status, components: { controlPlane, database, rabbitmq } }` |

### Organizations

| Method | Path | Purpose | Request Body | Response |
|---|---|---|---|---|
| `POST` | `/api/v1/organizations` | Create org | `{ name, slug, description }` | `OrganizationResponse` |
| `GET` | `/api/v1/organizations/{id}` | Get org | — | `OrganizationResponse` |
| `GET` | `/api/v1/organizations` | List all | — | `List<OrganizationResponse>` |
| `PUT` | `/api/v1/organizations/{id}` | Update org | `{ name, description }` | `OrganizationResponse` |
| `DELETE` | `/api/v1/organizations/{id}` | Delete org | — | `204 No Content` |

### Projects

| Method | Path | Purpose | Request Body / Params | Response |
|---|---|---|---|---|
| `POST` | `/api/v1/projects` | Create project | `{ organizationId, name, slug, description }` | `ProjectResponse` |
| `GET` | `/api/v1/projects/{id}` | Get project | — | `ProjectResponse` |
| `GET` | `/api/v1/projects?organizationId=X` | List by org | query param | `List<ProjectResponse>` |
| `PUT` | `/api/v1/projects/{id}` | Update project | `{ name, description }` | `ProjectResponse` |
| `DELETE` | `/api/v1/projects/{id}` | Delete project | — | `204 No Content` |

### Repositories

| Method | Path | Purpose | Request Body / Params | Response |
|---|---|---|---|---|
| `POST` | `/api/v1/repositories` | Create repo | `{ projectId, provider, repositoryUrl, repositoryName, defaultBranch }` | `RepositoryResponse` |
| `GET` | `/api/v1/repositories/{id}` | Get repo | — | `RepositoryResponse` |
| `GET` | `/api/v1/repositories?projectId=X` | List by project | query param | `List<RepositoryResponse>` |
| `PUT` | `/api/v1/repositories/{id}` | Update repo | `{ repositoryUrl, repositoryName, defaultBranch, status }` | `RepositoryResponse` |
| `DELETE` | `/api/v1/repositories/{id}` | Delete repo | — | `204 No Content` |
| `GET` | `/api/v1/repositories/{id}/runs` | Get runs for repo | — | `List<RunResponse>` |

### Pipelines

| Method | Path | Purpose | Request Body / Params | Response |
|---|---|---|---|---|
| `POST` | `/api/v1/pipelines` | Create pipeline | `{ projectId, name, description }` | `PipelineResponse` |
| `GET` | `/api/v1/pipelines/{id}` | Get pipeline | — | `PipelineResponse` |
| `GET` | `/api/v1/pipelines?projectId=X` | List by project | query param | `List<PipelineResponse>` |
| `PUT` | `/api/v1/pipelines/{id}` | Update pipeline | `{ name, description, status }` | `PipelineResponse` |
| `DELETE` | `/api/v1/pipelines/{id}` | Delete pipeline | — | `204 No Content` |
| `POST` | `/api/v1/pipelines/{id}/versions` | Submit YAML to pipeline | `{ yamlContent }` | `PipelineVersionResponse` |
| `POST` | `/api/v1/pipelines/yaml?projectId=X` | Submit YAML to project | `{ yamlContent }` | `PipelineVersionResponse` |
| `GET` | `/api/v1/pipelines/{id}/versions` | List versions | — | `List<PipelineVersionResponse>` |
| `GET` | `/api/v1/pipelines/{id}/versions/{versionId}` | Get version detail | — | `PipelineVersionDetailResponse` |
| `GET` | `/api/v1/pipelines/{id}/runs` | Get runs | — | `List<RunResponse>` |

### Runs

| Method | Path | Purpose | Request Body | Response |
|---|---|---|---|---|
| `POST` | `/api/v1/runs` | Trigger a run | `{ pipelineVersionId, commitSha, branch, repositoryId?, triggeredBy? }` | `RunResponse` |
| `GET` | `/api/v1/runs/{id}` | Get run | — | `RunResponse` |
| `GET` | `/api/v1/runs?versionId=X` | List by version | query param | `List<RunResponse>` |
| `POST` | `/api/v1/runs/{id}/cancel` | Cancel run | — | `RunResponse` |
| `GET` | `/api/v1/runs/{id}/stages` | Get stages | — | `List<StageResponse>` |
| `GET` | `/api/v1/runs/{runId}/stages/{stageId}/jobs` | Get jobs in stage | — | `List<JobResponse>` |
| `GET` | `/api/v1/runs/{runId}/stages/{stageId}/jobs/{jobId}/attempts` | Get job attempts | — | `List<AttemptResponse>` |

### Artifacts

| Method | Path | Purpose | Request Params | Response |
|---|---|---|---|---|
| `POST` | `/api/v1/artifacts` | Create artifact | `pipelineRunId, artifactType, name, locationUrl, jobId?` (query params) | `ArtifactResponse` |
| `GET` | `/api/v1/artifacts/{id}` | Get artifact | — | `ArtifactResponse` |
| `GET` | `/api/v1/artifacts?pipelineRunId=X` | List by run | query param | `List<ArtifactResponse>` |
| `DELETE` | `/api/v1/artifacts/{id}` | Delete artifact | — | `204 No Content` |

### Deployments

| Method | Path | Purpose | Request Params | Response |
|---|---|---|---|---|
| `POST` | `/api/v1/deployments` | Create deployment | `pipelineRunId, environment` (query params) | `DeploymentResponse` |
| `GET` | `/api/v1/deployments/{id}` | Get deployment | — | `DeploymentResponse` |
| `GET` | `/api/v1/deployments?pipelineRunId=X` or `?environment=Y` | List deployments | query params | `List<DeploymentResponse>` |
| `POST` | `/api/v1/deployments/{id}/start` | Start deployment | — | `DeploymentResponse` |
| `POST` | `/api/v1/deployments/{id}/complete` | Complete deployment | `success, endpoint?` (query params) | `DeploymentResponse` |
| `DELETE` | `/api/v1/deployments/{id}` | Delete deployment | — | `204 No Content` |

### Webhooks

| Method | Path | Purpose | Headers | Response |
|---|---|---|---|---|
| `POST` | `/api/v1/webhooks/{provider}` | Receive webhook | Provider-specific (X-GitHub-Event, X-Hub-Signature-256, etc.) | `WebhookEventResponse` |
| `GET` | `/api/v1/webhooks/{id}` | Get event | — | `WebhookEventResponse` |

### Error Response Format

All error responses use `ApiErrorResponse`:

```json
{
  "code": "RESOURCE_NOT_FOUND",
  "message": "Pipeline not found with id: ...",
  "details": {},
  "timestamp": "2025-01-15T10:30:00Z"
}
```

| HTTP Status | Error Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Request body validation fails |
| 404 | `RESOURCE_NOT_FOUND` | Entity not found |
| 409 | `RESOURCE_CONFLICT` | Unique constraint violation |
| 422 | `BUSINESS_RULE_VIOLATION` | Business rule fails (e.g., pipeline not ACTIVE) |
| 422 | `PIPELINE_VALIDATION_ERROR` | YAML validation fails |
| 500 | `RUN_EXECUTION_ERROR` | Run execution error |
| 500 | `INTERNAL_ERROR` | Unexpected exception |

---

## 13. Testing

### Backend Tests

| Test | File | What It Verifies |
|---|---|---|
| `PipelineConfigMapperTest` | `test/.../pipeline/PipelineConfigMapperTest.java` | Maps PipelineConfig → StageDefinitions correctly, resolves job types, handles null dependsOn |
| `PipelineOrchestratorTest` | `test/.../execution/PipelineOrchestratorTest.java` | Stage/job creation, dependency evaluation, job dispatch on completion, retry logic, cancellation, run completion |
| `JobDispatcherServiceTest` | `test/.../execution/JobDispatcherServiceTest.java` | dispatchReadyJobs evaluates stage/job deps, dispatchJob creates attempt and sends message, dispatchForRetry, positional ordering fallback, failed stage blocking |
| `RabbitMQIntegrationTest` | `test/.../execution/RabbitMQIntegrationTest.java` | Full integration: submit YAML → trigger run → verify run created with correct status |
| `RunApiTest` | `test/.../api/RunApiTest.java` | REST API: trigger run, get run, list runs, cancel run, get stages, get jobs, get attempts — with validation errors |
| `RunServiceTest` | `test/.../execution/RunServiceTest.java` | triggerRun validates pipeline status, creates run with QUEUED status, delegates to orchestrator |
| `YamlPipelineFlowTest` | `test/.../pipeline/YamlPipelineFlowTest.java` | End-to-end YAML flow: submit YAML → parse → validate → create pipeline → create version → trigger run → verify stages/jobs |
| `DependencyValidatorTest` | `test/.../pipeline/validator/DependencyValidatorTest.java` | Detects cyclic dependencies in stages and jobs, passes valid configs |
| `SchemaValidatorTest` | `test/.../pipeline/validator/SchemaValidatorTest.java` | Required field validation, job type validation, size limits |
| `SemanticValidatorTest` | `test/.../pipeline/validator/SemanticValidatorTest.java` | Unique names, dependency reference validation |
| `PipelineYamlServiceTest` | `test/.../pipeline/PipelineYamlServiceTest.java` | YAML submission, validation, version creation, pipeline creation |
| `PipelineYamlParserTest` | `test/.../pipeline/PipelineYamlParserTest.java` | Parses valid YAML, rejects empty/malformed YAML |
| `JobMessageConsumerTest` | `test/.../execution/JobMessageConsumerTest.java` | Consumer handles success, failure, cancellation, duplicate messages |
| `StageResultCollectorTest` | `test/.../execution/StageResultCollectorTest.java` | Evaluates stage/run statuses from child statuses |
| `OutboxEventServiceTest` | `test/.../execution/OutboxEventServiceTest.java` | Event creation, mark published, mark failed, get pending |
| `CreationDispatchFlowTest` | `test/.../CreationDispatchFlowTest.java` | Pipeline creation → YAML submission → version → run trigger flow |
| `EndToEndExecutionTest` | `test/.../EndToEndExecutionTest.java` | Full lifecycle: create org → project → pipeline → YAML → version → run → verify stages/jobs |
| `HealthControllerTest` | `test/.../health/HealthControllerTest.java` | Health endpoint returns component status |
| `ExecutionLoggerTest` | `test/.../execution/worker/ExecutionLoggerTest.java` | Logs job start, step completion, errors |
| `GitOperationsTest` | `test/.../execution/worker/GitOperationsTest.java` | URL sanitization, git clone/checkout/verify |
| `StepExecutorTest` | `test/.../execution/worker/StepExecutorTest.java` | Executes commands, handles timeouts |
| `WorkerExecutorTest` | `test/.../execution/worker/WorkerExecutorTest.java` | Executes jobs, auto-detects build tools |
| `WorkspaceManagerTest` | `test/.../execution/worker/WorkspaceManagerTest.java` | Creates workspace dirs, path traversal prevention, cleanup |
| `RunControllerIntegrationTest` | `test/.../api/RunControllerIntegrationTest.java` | REST integration for run operations |
| `PipelineYamlApiTest` | `test/.../api/PipelineYamlApiTest.java` | REST integration for YAML submission endpoints |
| `DomainCrudIntegrationTest` | `test/.../DomainCrudIntegrationTest.java` | CRUD for all domain entities via REST |
| `DomainRepositoryIntegrationTest` | `test/.../DomainRepositoryIntegrationTest.java` | Repository layer queries and constraints |
| `RepositoryServiceTest` | `test/.../domain/service/RepositoryServiceTest.java` | Repository service CRUD operations |
| `ProjectServiceTest` | `test/.../domain/service/ProjectServiceTest.java` | Project service CRUD operations |
| `PipelineServiceTest` | `test/.../domain/service/PipelineServiceTest.java` | Pipeline service CRUD and version operations |
| `OrganizationServiceTest` | `test/.../domain/service/OrganizationServiceTest.java` | Organization service CRUD operations |

### Worker Tests

| Test | File | What It Verifies |
|---|---|---|
| `PipelineParserTest` | `test/.../pipeline/PipelineParserTest.java` | Worker-side YAML parsing |
| `PipelineValidatorTest` | `test/.../pipeline/PipelineValidatorTest.java` | Worker-side pipeline validation |
| `DuplicateJobGuardTest` | `test/.../service/DuplicateJobGuardTest.java` | tryAcquire, markCompleted, markFailed, TTL eviction |
| `PipelineJobValidatorTest` | `test/.../service/PipelineJobValidatorTest.java` | Job message validation |
| `CommandSecurityPolicyTest` | `test/.../security/CommandSecurityPolicyTest.java` | Command security policy enforcement |
| `ProcessExecutionSandboxTest` | `test/.../sandbox/ProcessExecutionSandboxTest.java` | Process-level sandbox execution |
| `JGitGitServiceTest` | `test/.../git/JGitGitServiceTest.java` | JGit clone, checkout, verify |
| `RabbitMqFlowIT` | `test/.../messaging/RabbitMqFlowIT.java` | RabbitMQ integration: publish/consume/ack/nack |
| `JobExecutorTest` | `test/.../execution/JobExecutorTest.java` | Job execution flow |
| `PipelineExecutorTest` | `test/.../execution/PipelineExecutorTest.java` | Pipeline execution flow |
| `StepExecutorTest` | `test/.../execution/StepExecutorTest.java` | Step-level command execution |
| `CommandTimeoutIT` | `test/.../execution/CommandTimeoutIT.java` | Command timeout handling |

### Known Test Issues

`DomainRepositoryIntegrationTest` has 3 H2-specific failures due to H2 not
enforcing `UNIQUE` constraints at flush time in `@Transactional` test
scenarios:
- `organizationSlugShouldBeUnique`
- `projectSlugShouldBeUniquePerOrg`
- `webhookDeliveryIdShouldBeUnique`

These pass on PostgreSQL (the production database).

---

## 14. Project Execution

### Prerequisites

| Requirement | Version |
|---|---|
| Java (JDK) | 21+ |
| Maven | 3.9+ |
| PostgreSQL | 13+ |
| RabbitMQ | 3.12+ |
| Docker | Optional (for Docker Compose) |

### Configuration

Key environment variables (all have defaults in `.env.example`):

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | PostgreSQL host |
| `POSTGRES_PORT` | `5432` | PostgreSQL port |
| `POSTGRES_DB` | `cicd` | Database name |
| `POSTGRES_USER` | `cicd` | Database user |
| `POSTGRES_PASSWORD` | `cicd` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | RabbitMQ port |
| `RABBITMQ_USERNAME` | `guest` | RabbitMQ user |
| `RABBITMQ_PASSWORD` | `guest` | RabbitMQ password |
| `SERVER_PORT` | `8081` | API server port |
| `WORKSPACE_BASE_PATH` | `workspace` | Job workspace root |
| `WORKSPACE_TIMEOUT` | `3600` | Job timeout (seconds) |
| `WORKSPACE_MAX_RETRIES` | `3` | Max retry attempts |
| `WORKSPACE_RETRY_ENABLED` | `true` | Enable retries |
| `WORKER_ID` | `worker-local` | Worker identifier |
| `WORKER_CONCURRENCY` | `1` | Consumer concurrency |

### Running Locally

```bash
# From the project root
cp .env.example .env

# Using Docker Compose (recommended)
docker compose up --build

# Or running the backend directly
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=default

# Running the worker separately
cd worker
mvn spring-boot:run
```

### Running Tests

```bash
cd backend
mvn test                        # unit tests
mvn verify                      # unit + integration tests

cd worker
mvn test                        # unit tests
mvn verify -Pfailsafe           # unit + integration tests (*IT.java)
```

### API Endpoints

| Service | URL |
|---|---|
| API (Control Plane) | `http://localhost:8081/api/v1/health` |
| Swagger UI | `http://localhost:8081/swagger-ui.html` |
| API Docs | `http://localhost:8081/api-docs` |
| Worker Health | `http://localhost:8082/actuator/health` |
| RabbitMQ UI | `http://localhost:15672` |
| PostgreSQL | `localhost:5432` |

---

## 15. Example End-to-End Execution

### Step 1: Create Organization, Project, Pipeline

```bash
# Create organization
curl -X POST http://localhost:8081/api/v1/organizations \
  -H "Content-Type: application/json" \
  -d '{"name":"My Org","slug":"my-org"}'

# Create project (use org ID from response)
curl -X POST http://localhost:8081/api/v1/projects \
  -H "Content-Type: application/json" \
  -d '{"organizationId":"<org-id>","name":"Backend","slug":"backend"}'

# Create pipeline (use project ID)
curl -X POST http://localhost:8081/api/v1/pipelines \
  -H "Content-Type: application/json" \
  -d '{"projectId":"<project-id>","name":"CI Pipeline"}'
```

### Step 2: Submit Pipeline YAML

```yaml
pipeline:
  name: CI Pipeline
  description: Build and test
  stages:
    - name: build
      jobs:
        - name: compile
          type: BUILD
    - name: test
      dependsOn:
        - build
      jobs:
        - name: unit-test
          type: TEST
        - name: code-scan
          type: SCAN
          dependsOn:
            - unit-test
```

```bash
curl -X POST http://localhost:8081/api/v1/pipelines/<pipeline-id>/versions \
  -H "Content-Type: application/json" \
  -d '{"yamlContent":"<yaml above>"}'
```

**What happens internally:**
1. YAML is parsed by `PipelineYamlParser` → `PipelineConfig`
2. `SchemaValidator` validates structure
3. `SemanticValidator` validates semantics (unique names, references exist)
4. `DependencyValidator` checks for cycles (none found)
5. A new `PipelineVersion` is created with version=1 and the YAML content

### Step 3: Trigger a Run

```bash
curl -X POST http://localhost:8081/api/v1/runs \
  -H "Content-Type: application/json" \
  -d '{
    "pipelineVersionId": "<version-id>",
    "commitSha": "abc123def456",
    "branch": "main",
    "triggeredBy": "manual-test"
  }'
```

**What happens internally:**

1. `RunService.triggerRun()`:
   - Loads version and pipeline, validates pipeline is ACTIVE
   - Creates `PipelineRun` with status=QUEUED
   - Records audit event
   - Calls `orchestrator.startExecution(run)`

2. `PipelineOrchestrator.startExecution()`:
   - Parses YAML → `PipelineConfig`
   - Creates stages: `build` (orderIndex=0), `test` (orderIndex=1)
   - Creates jobs: `compile` (BUILD) in `build`, `unit-test` (TEST) and `code-scan` (SCAN) in `test`
   - Sets run status → RUNNING
   - Publishes `RUN_STARTED` outbox event
   - Calls `dispatchReadyJobs(runId)`

3. `JobDispatcherService.dispatchReadyJobs()`:
   - Builds dependency map: `{build: [], test: [build]}`
   - Stage `build` (orderIndex=0): no deps → eligible
   - Stage `test` (orderIndex=1): depends on `build` → `build` is PENDING → NOT ready
   - Job `compile` (PENDING, no job deps) → dispatchJob()

4. `JobDispatcherService.dispatchJob()`:
   - Sets job `compile` → QUEUED
   - Creates `JobAttempt` (attemptNumber=1, status=PENDING)
   - Publishes `JobDispatchMessage` to `pipeline-jobs-exchange`

5. `JobMessageConsumer.onJobDispatch()`:
   - Finds job (QUEUED) and run (RUNNING)
   - Sets job → RUNNING, attempt → RUNNING
   - Creates workspace, builds `ExecutionContext`
   - `WorkerExecutor.executeJob()`:
     - Clones repo (if git URL provided)
     - Detects `pom.xml` → runs `mvn clean install -DskipTests`
     - Returns success/failure
   - Updates attempt status
   - Calls `orchestrator.handleJobCompletion()`

6. `PipelineOrchestrator.handleJobCompletion()`:
   - Sets `compile` → SUCCESS (exitCode=0)
   - Checks if stage `build` is complete → yes (only `compile`)
   - Evaluates stage status → SUCCESS
   - Sets stage `build` → SUCCESS
   - Checks if all stages complete → no (test still pending)
   - Calls `dispatchReadyJobs()` again
   - Stage `test`: depends on `build` → `build` is SUCCESS → eligible
   - Job `unit-test` (no job deps) → dispatch
   - Job `code-scan` (depends on `unit-test`) → NOT ready yet

7. After `unit-test` completes (SUCCESS):
   - `dispatchReadyJobs()` called again
   - `code-scan` now eligible → dispatch

8. After `code-scan` completes:
   - Stage `test` → SUCCESS
   - All stages complete → run → SUCCESS

### Final Status

```
PipelineRun:    SUCCESS
PipelineStage:  build=SUCCESS,  test=SUCCESS
PipelineJob:    compile=SUCCESS, unit-test=SUCCESS, code-scan=SUCCESS
JobAttempt:     1=SUCCESS (each job)
OutboxEvents:   RUN_STARTED, JOB_COMPLETED×3, STAGE_COMPLETED×2, RUN_COMPLETED
```

---

## 16. Error Handling

### Invalid YAML
- **Where**: `PipelineYamlParser.parse()` → `PipelineYamlParseException`
- **Handled by**: `PipelineYamlService.parseYaml()` → `PipelineValidationException`
- **Response**: HTTP 422 with `PIPELINE_VALIDATION_ERROR` and field-level errors

### Invalid Dependency Reference
- **Where**: `SemanticValidator.validateStageDependenciesExist()` /
  `validateJobDependenciesExist()`
- **Code**: `INVALID_REFERENCE`
- **Response**: HTTP 422 with error detail like
  `"Stage 'deploy' depends on unknown stage 'build'"`

### Cyclic Dependency
- **Where**: `DependencyValidator.detectStageCycles()` /
  `detectJobCycles()` using DFS
- **Code**: `CYCLIC_DEPENDENCY`
- **Response**: HTTP 422 with error detail like
  `"Circular dependency detected among pipeline stages"`

### Missing Resource
- **Where**: Any `*Repository.findById()` → `ResourceNotFoundException`
- **Response**: HTTP 404 with `RESOURCE_NOT_FOUND`

### Invalid API Request
- **Where**: Jakarta Bean Validation (`@Valid`) → `MethodArgumentNotValidException`
- **Handled by**: `GlobalExceptionHandler.handleValidation()`
- **Response**: HTTP 400 with `VALIDATION_FAILED` and field-level details

### Worker Failure
- **Where**: `PipelineJobConsumer` catches `WorkerException`
- **Action**: If retries left → route to delay queue; otherwise → reject to DLQ
  and publish FAILED result

### RabbitMQ Failure
- **Where**: `OutboxEventPublisher.publishPendingEvents()` catches Exception
- **Action**: Marks outbox event as FAILED, logs error, continues with next event
- **Job dispatch**: If `RabbitTemplate.convertAndSend()` fails, exception
  propagates from `JobDispatcherService.dispatchJob()`

### Duplicate Message
- **Control plane**: `JobMessageConsumer` checks job status, ACKs duplicates
- **Worker**: `DuplicateJobGuard.tryAcquire()` returns false, ACKs duplicates

### Retry Failure (Exhausted)
- **Control plane**: After max retries, `handleJobCompletion()` falls through
  to normal failure propagation (stage → run FAILED)
- **Worker**: After max retries, `PipelineJobConsumer` publishes FAILED result
  and rejects to DLQ

---

## 17. Observability

### Implemented Logging

**Structured log pattern** (`application.yml`):
```
%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [correlationId=X] [run=X stage=X job=X attempt=X worker=X] %logger{36} - %msg%n
```

**MDC context** (`ExecutionMdc`): runId, stageId, jobId, attemptId, workerId
are set at each processing stage and cleared in `finally` blocks.

**Key log messages (control plane)**:

| Log Tag | When | Key Fields |
|---|---|---|
| `[RUN_STARTED]` | Orchestrator starts run | runId, status |
| `[STAGE_CREATED]` | Stage entity created | stageId, stageName, orderIndex |
| `[DISPATCH_READY]` | Dispatching eligible jobs | runId, stagesCount |
| `[JOB_DISPATCHED]` | Job sent to RabbitMQ | jobId, jobName, attemptNumber, stageId, runId |
| `[RUN_TRIGGERED]` | Run created | runId, branch, commitSha, triggeredBy |
| `[JOB_RECEIVED]` | Consumer picks up message | jobId, jobName, runId, attemptNumber |
| `[JOB_STARTED]` | Worker begins execution | jobId, jobName, jobType, attemptNumber, workerId |
| `[JOB_COMPLETED]` | Job succeeded | jobId, exitCode, status=SUCCESS |
| `[JOB_FAILED]` | Job failed | jobId, exitCode, status=FAILED |
| `[JOB_RETRY]` | Retrying job | jobId, attempt, maxRetries |
| `[JOB_RETRY_EXHAUSTED]` | Retries used up | jobId, attempt, maxRetries |
| `[STAGE_COMPLETED]` | Stage finished | stageId, stageName, status |
| `[RUN_COMPLETED]` | Run finished | runId, status |
| `[RUN_CANCELLED]` | Run cancelled | runId |
| `[JOB_CANCELLED]` | Job cancelled | jobId, previousStatus |
| `[JOB_SKIPPED]` | Job skipped (cancelled/duplicate) | jobId, reason |

**Key log messages (worker)**:

| Log Tag | When | Key Fields |
|---|---|---|
| `[JOB_START]` | Execution begins | runId, jobId, jobName, branch, commitSha |
| `[JOB_COMPLETE]` | Execution ends | runId, jobId, status, exitCode |
| `[STEP_COMPLETE]` | Command finished | step, exitCode, status |
| `[ERROR]` | Error occurred | message |
| `[CANCELLATION]` | Run cancelled | runId |

**Outbox events logged**: `RUN_STARTED`, `JOB_COMPLETED`, `STAGE_COMPLETED`,
`RUN_COMPLETED`, `RUN_CANCELLED`.

### Not Implemented (Future Improvements)

- Distributed tracing (OpenTelemetry/Jaeger)
- Prometheus/Grafana metrics dashboards (worker has basic Actuator metrics)
- Structured log aggregation (ELK/Loki)
- Distributed log correlation across control plane and worker
- Alerting on failure patterns

---

## 18. Security

### Implemented

| Mechanism | Details |
|---|---|
| **Webhook signature verification** | `WebhookController` verifies GitHub HMAC-SHA256 signatures (`X-Hub-Signature-256`) and GitLab tokens (`X-Gitlab-Token`) using constant-time comparison |
| **Path traversal prevention** | `WorkspaceManager.createWorkspace()` validates that the resolved path starts with the base path; rejects paths with `..` components |
| **Git URL sanitization** | `GitOperations.sanitizeUrl()` redacts credentials from URLs in log messages |
| **Job message URL redaction** | `PipelineJobConsumer.redactUrl()` redacts credentials before logging |
| **Secret scrubbing** | `PipelineJobConsumer.safeBody()` strips password/token/secret/credential values from malformed message bodies before logging |
| **Input validation** | Jakarta Bean Validation (`@NotBlank`, `@NotNull`, `@Valid`) on all request DTOs |
| **Pipeline validation** | Multi-stage validation before execution prevents injection of invalid or dangerous pipeline configurations |
| **Command security policy** | Worker has `CommandSecurityPolicy` with configurable policy levels (STRICT) |
| **Database constraints** | CHECK constraints, UNIQUE constraints, foreign keys prevent data corruption |

### Not Implemented

| Mechanism | Status |
|---|---|
| Authentication (JWT, OAuth2, API keys) | Future Enhancement |
| Authorization (RBAC) | Future Enhancement |
| TLS/mTLS | Not implemented (assumed handled at infrastructure level) |
| Secrets management (Vault, Azure Key Vault) | Future Enhancement |
| Rate limiting | Not implemented |
| CORS configuration | Not implemented |
| API key rotation | Not implemented |

---

## 19. Current Project Status

| Area | Status |
|---|---|
| YAML parsing | **Complete** — SnakeYAML with type-safe PipelineRoot mapping |
| YAML schema validation | **Complete** — SchemaValidator with name/size/type checks |
| YAML semantic validation | **Complete** — unique names, reference existence |
| YAML dependency validation | **Complete** — DFS cycle detection for stages and jobs |
| Pipeline creation | **Complete** — CRUD with ACTIVE/INACTIVE/ARCHIVED status |
| Pipeline versioning | **Complete** — immutable versions with auto-increment |
| Run creation | **Complete** — API and webhook triggers |
| Stage creation | **Complete** — from YAML with orderIndex |
| Job creation | **Complete** — from YAML with resolved JobType |
| Stage dependsOn | **Complete** — explicit and positional ordering |
| Job dependsOn | **Complete** — within-stage job dependencies |
| Pipeline orchestration | **Complete** — start, complete, retry, cancel |
| Job dispatch | **Complete** — with dependency evaluation |
| RabbitMQ (control plane) | **Complete** — dispatch, consume, manual ACK |
| RabbitMQ (worker) | **Complete** — jobs exchange, delay queue, DLQ, results |
| Worker execution | **Complete** — git clone, command detection, process execution |
| Retry handling | **Complete** — application-level (control plane) + infrastructure-level (worker) |
| Failure propagation | **Complete** — job → stage → run |
| Cancellation | **Partial** — marks PENDING/QUEUED jobs CANCELLED, does NOT kill RUNNING processes |
| Duplicate message handling | **Complete** — status check (control plane) + DuplicateJobGuard (worker) |
| Outbox events | **Complete** — transactional write + scheduled publish |
| Webhook integration | **Partial** — receives GitHub/GitLab webhooks, HMAC verification, triggers runs |
| Domain CRUD (org/project/repo) | **Complete** |
| Artifacts tracking | **Complete** — API exists, storage is local filesystem |
| Deployment tracking | **Complete** — API exists, no actual cloud deployment |
| Database schema | **Complete** — 14 tables with Flyway migrations |
| Testing | **Comprehensive** — 30+ backend tests, 15+ worker tests |
| Security | **Partial** — webhook auth, path traversal, input validation; no user auth |
| Production readiness | **Partial** — missing monitoring, alerting, HA, auth |

---

## 20. Known Limitations

### Core Limitations

1. **Cancellation does not kill running processes**: `cancelRun()` marks
   PENDING/QUEUED jobs as CANCELLED but does not forcibly terminate OS processes
   spawned by RUNNING jobs. The cancelled status is only checked when the next
   message is consumed.

2. **No cross-process duplicate protection**: `DuplicateJobGuard` is in-memory
   (`ConcurrentHashMap`). If multiple worker instances consume the same queue,
   the same job could execute on different workers. Cross-process dedup requires
   a distributed store (e.g., Redis or database lock).

3. **Dependency maps rebuilt per dispatch**: `JobDispatcherService` re-parses
   the YAML and rebuilds dependency maps on every `dispatchReadyJobs()` call.
   This is correct but inefficient for large pipelines.

4. **Worker embedded in control plane**: The `JobMessageConsumer` +
   `WorkerExecutor` components in the backend execute jobs inline. The separate
   worker service also exists. Running both creates confusion; the architecture
   supports both modes but deployment needs to choose one.

5. **No distributed locking**: Concurrent API calls to trigger the same pipeline
   version could create duplicate runs. No optimistic or pessimistic locking
   prevents this.

6. **Stage dependsOn defaults to sequential**: If no explicit `dependsOn` is
   given, all previous stages must succeed. This means you cannot run stages
   in parallel unless you use explicit `dependsOn` to define a DAG.

### Production-Hardening Limitations

1. **No authentication or authorization**: All API endpoints are publicly
   accessible. No JWT, OAuth2, or API key validation.

2. **No metrics or tracing**: Backend has no Prometheus metrics or distributed
   tracing. Worker has basic Actuator/Prometheus but no dashboards.

3. **No log aggregation**: Structured logs go to stdout. No ELK, Loki, or
   centralized logging.

4. **No TLS termination**: Application does not configure HTTPS. TLS assumed
   at load balancer/proxy level.

5. **No horizontal scaling**: Single-instance deployment assumed. Worker
   concurrency is configurable but limited by local resources.

6. **No persistent retry backoff**: Worker retry uses fixed delay (`retry-delay-ms`).
   No exponential backoff or jitter.

7. **No pipeline dry-run**: Cannot validate a pipeline YAML without persisting
   it as a version.

8. **No build artifact storage backend**: Artifacts are stored locally in the
   workspace directory. No S3/ACR integration.

---

## 21. Future Enhancements

| Area | Description | Current Status |
|---|---|---|
| Authentication | JWT/OAuth2/OIDC integration (Keycloak) | Not implemented — Keycloak config exists in docker-compose but not wired |
| Authorization | Role-based access control per organization/project | Not implemented |
| Metrics & Monitoring | Prometheus metrics, Grafana dashboards, alerting | Worker has basic Actuator; backend has none |
| Distributed Tracing | OpenTelemetry/Jaeger for cross-service request tracing | Not implemented |
| CI/CD Platform UI | React frontend for pipeline management and run monitoring | Phase 0 skeleton exists |
| Kubernetes Deployment | Helm charts, k8s manifests for production deployment | Terraform skeleton exists for Azure |
| Advanced Scheduling | Cron-based pipeline triggers, scheduled runs | Not implemented |
| Pipeline Conditions | `when:` clauses on stages/jobs for conditional execution | Not implemented |
| Artifact Storage | S3/Azure Blob/ACR integration for build artifacts | API exists, storage is local |
| Cloud Deployment | Azure Container Apps / AKS actual deployment | API exists, no cloud integration |
| GitHub Integration | Full webhook → pipeline → deployment automation | Partially implemented (webhook receive + HMAC) |
| GitLab/Bitbucket | Webhook support for additional providers | WebhookController supports providers but limited processing |
| Secret Management | Azure Key Vault / HashiCorp Vault integration | Not implemented |
| Pipeline Templates | Reusable pipeline templates with parameterization | Not implemented |
| Parallel Stage Execution | True parallel stages with DAG scheduling | dependsOn supports DAG but execution is synchronous within dispatch |
| Log Streaming | Real-time log streaming for running jobs | Not implemented |
| Audit Dashboard | Query and visualize audit events | AuditEvent entity exists; no UI |

---

## 22. Interview Explanation

### 2-Minute Explanation

> I built an enterprise CI/CD orchestration platform that separates the control
> plane from the execution plane. The control plane is a Spring Boot API that
> accepts pipeline definitions as YAML, validates them through three
> validators (schema, semantic, and dependency cycle detection), and persists
> them as immutable versions in PostgreSQL.
>
> When a run is triggered via REST API or GitHub webhook, the orchestrator
> parses the YAML, creates stage and job entities, evaluates `dependsOn`
> dependencies at both stage and job levels, and dispatches eligible jobs to
> RabbitMQ. The worker consumes these messages, clones the repository, auto-detects
> the build tool, executes commands, and publishes results back.
>
> The system handles retries (both application-level in the orchestrator and
> infrastructure-level in the worker via a delay queue), failure propagation
> from job to stage to run, cancellation of pending jobs, duplicate message
> prevention, and records every lifecycle event through a transactional outbox
> pattern.
>
> The database has 14 tables covering organizations, projects, repositories,
> pipelines, versions, runs, stages, jobs, attempts, outbox events, webhook
> events, artifacts, deployments, and audit events.

### 5-Minute Deep Dive

**Architecture decisions:**
- Modular monolith control plane (not microservices) — simpler to develop and
  deploy while maintaining logical separation. The worker is a separate service
  because it executes untrusted code.
- PostgreSQL as source of truth for all durable state. No Redis caching layer
  yet (deferred until measurements justify it).
- RabbitMQ for asynchronous job dispatch with manual ACK for reliability.
- Transactional outbox pattern for reliable event publishing — events are
  written in the same DB transaction as state changes, then polled and
  published to RabbitMQ.

**Pipeline lifecycle:**
1. YAML submitted → parsed by SnakeYAML into `PipelineConfig`
2. Three-phase validation: schema (structure) → semantic (rules) → dependency
   (cycle detection via DFS)
3. Persisted as immutable `PipelineVersion` with auto-incrementing version number
4. Run triggered → `PipelineRun` created → orchestrator creates `PipelineStage`
   and `PipelineJob` entities from YAML definitions
5. Dispatcher evaluates dependencies, dispatches eligible jobs to RabbitMQ
6. Consumer receives, executes, updates status, evaluates completion

**Dependency handling:**
- Stage-level: explicit `dependsOn` list of stage names, OR positional ordering
  (all previous stages must be SUCCESS)
- Job-level: explicit `dependsOn` list of sibling job names within the same stage
- Dependencies are rebuilt from YAML on each dispatch cycle (correctness over
  performance)
- All dependencies must be SUCCESS; PENDING, RUNNING, FAILED, or SKIPPED
  means blocked

**RabbitMQ flow:**
- Control plane publishes `JobDispatchMessage` to `pipeline-jobs-exchange`
- Consumer listens on `pipeline-jobs` queue with manual ACK
- Worker has separate topology with delay queue for retry (TTL-based)
- Messages that exhaust retries go to dead-letter queue

**Retries:**
- Application-level: orchestrator checks `retryEnabled && attemptCount < maxRetries`,
  creates new `JobAttempt`, re-dispatches
- Worker-level: catches `WorkerException`, checks `x-retry-count` header,
  publishes to delay queue

**Failure handling:**
- Job failure → Stage status = FAILED (if any job FAILED) → Run status = FAILED
  (if any stage FAILED)
- Failed stages block dependent stages
- Cancellation sets PENDING/QUEUED → CANCELLED, PENDING stages → SKIPPED

**Database design:**
- 14 tables with UUID primary keys, CHECK constraints, foreign keys
- Hierarchical: Organization → Project → Pipeline → PipelineVersion →
  PipelineRun → PipelineStage → PipelineJob → JobAttempt
- WebhookEvent for idempotent webhook ingestion (unique on provider + deliveryId)
- OutboxEvent for reliable event publishing
- All timestamps are UTC (TIMESTAMPTZ)

**Key engineering decisions:**
- Manual ACK on RabbitMQ (not auto) for at-least-once delivery guarantee
- `DuplicateJobGuard` for in-process deduplication
- `WorkspaceManager` with path traversal prevention
- Git URL sanitization in logs (credential redaction)
- `ExecutionMdc` for structured logging with run/stage/job/attempt/worker IDs
- `PipelineConfigMapper` produces immutable records (`StageDefinition`,
  `JobDefinition`) carrying dependency information

---

## 23. Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT                                        │
│                     (API Consumer / Webhook)                                │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         REST API LAYER                                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐ │
│  │Pipeline  │ │   Run    │ │   Org    │ │   Webhook│ │     Health       │ │
│  │Controller│ │Controller│ │Controller│ │Controller│ │    Controller    │ │
│  └────┬─────┘ └────┬─────┘ └──────────┘ └────┬─────┘ └──────────────────┘ │
└───────┼─────────────┼─────────────────────────┼────────────────────────────┘
        │             │                         │
        ▼             ▼                         ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CONTROL PLANE                                       │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                    PIPELINE MANAGEMENT                               │    │
│  │  ┌──────────────┐  ┌────────────────┐  ┌──────────────────────┐   │    │
│  │  │PipelineYaml  │  │PipelineConfig  │  │  Domain Services     │   │    │
│  │  │Service       │  │Mapper          │  │  (Org/Project/Repo/  │   │    │
│  │  │              │──▶│                │  │   Pipeline CRUD)     │   │    │
│  │  └──────┬───────┘  └────────────────┘  └──────────────────────┘   │    │
│  │         │                                                         │    │
│  │  ┌──────▼────────────────────────────────────────────────────┐    │    │
│  │  │                 YAML PARSER / VALIDATOR                    │    │    │
│  │  │  ┌──────────────┐  ┌────────────────┐  ┌──────────────┐ │    │    │
│  │  │  │PipelineYaml  │  │SchemaValidator │  │SemanticValid. │ │    │    │
│  │  │  │Parser        │──▶│                │──▶│              │ │    │    │
│  │  │  └──────────────┘  └────────────────┘  └──────┬───────┘ │    │    │
│  │  │                                                │         │    │    │
│  │  │                              ┌─────────────────┘         │    │    │
│  │  │                              ▼                           │    │    │
│  │  │                        ┌──────────────┐                  │    │    │
│  │  │                        │DependencyVal.│ (cycle detection) │    │    │
│  │  │                        └──────────────┘                  │    │    │
│  │  └──────────────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                      RUN SERVICE                                    │    │
│  │  ┌──────────┐  ┌─────────────────┐  ┌────────────────────────┐   │    │
│  │  │RunService│──▶│PipelineOrchestr.│──▶│  StageResultCollector  │   │    │
│  │  └──────────┘  │                 │  └────────────────────────┘   │    │
│  │                │  startExecution │                                │    │
│  │                │  handleComplete │  ┌────────────────────────┐   │    │
│  │                │  cancelRun      │  │   Outbox Event System   │   │    │
│  │                └────────┬────────┘  │  OutboxEventService    │   │    │
│  │                         │           │  OutboxEventPublisher   │   │    │
│  └─────────────────────────┼───────────└────────────────────────┘   │    │
│                            │                                         │    │
│  ┌─────────────────────────▼──────────────────────────────────────┐  │    │
│  │                   JOB DISPATCHER                                │  │    │
│  │  ┌──────────────────────────────────────────────────────┐      │  │    │
│  │  │JobDispatcherService                                  │      │  │    │
│  │  │  - buildDependencyMap()                              │      │  │    │
│  │  │  - buildJobDependencyMap()                           │      │  │    │
│  │  │  - dispatchReadyJobs()                               │      │  │    │
│  │  │  - dispatchJob() / dispatchForRetry()                │      │  │    │
│  │  └──────────────────────────┬───────────────────────────┘      │  │    │
│  └─────────────────────────────┼──────────────────────────────────┘  │    │
│                                │                                     │    │
│  ┌─────────────────────────────▼──────────────────────────────────┐  │    │
│  │                  EMBEDDED WORKER                                │  │    │
│  │  ┌───────────────────┐  ┌──────────────┐  ┌───────────────┐  │  │    │
│  │  │JobMessageConsumer │──▶│WorkerExecutor│──▶│StepExecutor   │  │  │    │
│  │  │                   │  │              │  │(ProcessBuilder)│  │  │    │
│  │  └───────────────────┘  └──────────────┘  └───────────────┘  │  │    │
│  │  ┌───────────────────┐  ┌──────────────┐  ┌───────────────┐  │  │    │
│  │  │GitOperations      │  │WorkspaceMgr  │  │ExecutionLogger│  │  │    │
│  │  └───────────────────┘  └──────────────┘  └───────────────┘  │  │    │
│  └───────────────────────────────────────────────────────────────┘  │    │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           RABBITMQ                                          │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  Control Plane Topology          │  Worker Topology                  │   │
│  │  ─────────────────────           │  ────────────────                 │   │
│  │  pipeline-jobs-exchange          │  cicd.jobs.exchange               │   │
│  │    └─ pipeline-jobs queue        │    ├─ cicd.jobs (main)            │   │
│  │  pipeline-job-results-exchange   │    ├─ cicd.jobs.delay (TTL)       │   │
│  │    └─ pipeline-job-results queue │    └─ cicd.jobs.dlq (dead letter) │   │
│  │  outbox.exchange                 │  cicd.results.exchange            │   │
│  │    └─ outbox.queue               │    └─ cicd.result queue           │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────┬──────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                       SEPARATE WORKER SERVICE                               │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  PipelineJobConsumer → PipelineExecutionService → PipelineExecutor   │   │
│  │                       → StageExecutor → JobExecutor → StepExecutor   │   │
│  │  Sandbox: ProcessExecutionSandbox / DockerExecutionSandbox           │   │
│  │  PipelineResultPublisher → cicd.results.exchange                     │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────┬──────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          POSTGRESQL                                         │
│                                                                             │
│  organizations │ projects │ repositories │ pipelines │ pipeline_versions    │
│  pipeline_runs │ pipeline_stages │ pipeline_jobs │ job_attempts            │
│  webhook_events │ outbox_events │ artifacts │ deployments │ audit_events    │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 24. Final Repository Structure

```
DevOps-CI-CD-Automation-Platform/
│
├── backend/                              # Spring Boot Control Plane (Java 21)
│   ├── pom.xml                           # Maven build, dependencies
│   ├── Dockerfile                        # Container image build
│   └── src/
│       ├── main/
│       │   ├── java/com/cicd/platform/controlplane/
│       │   │   ├── ControlPlaneApplication.java          # Spring Boot entry point
│       │   │   │
│       │   │   ├── api/                                  # REST API layer
│       │   │   │   ├── config/
│       │   │   │   │   └── OpenApiConfig.java           # Swagger/OpenAPI config
│       │   │   │   ├── controller/
│       │   │   │   │   ├── HealthController.java         # GET /api/v1/health
│       │   │   │   │   ├── OrganizationController.java   # Org CRUD
│       │   │   │   │   ├── ProjectController.java        # Project CRUD
│       │   │   │   │   ├── RepositoryController.java     # Repo CRUD + runs
│       │   │   │   │   ├── PipelineController.java       # Pipeline CRUD + YAML + versions
│       │   │   │   │   ├── RunController.java            # Run trigger + query + cancel
│       │   │   │   │   ├── ArtifactController.java       # Artifact CRUD
│       │   │   │   │   ├── DeploymentController.java     # Deployment lifecycle
│       │   │   │   │   └── WebhookController.java        # Webhook ingestion
│       │   │   │   ├── dto/                              # 25 request/response records
│       │   │   │   └── exception/
│       │   │   │       ├── GlobalExceptionHandler.java   # Unified error handling
│       │   │   │       ├── ResourceNotFoundException.java
│       │   │   │       ├── ResourceConflictException.java
│       │   │   │       ├── BusinessRuleException.java
│       │   │   │       └── RunExecutionException.java
│       │   │   │
│       │   │   ├── domain/                               # Domain model
│       │   │   │   ├── entity/                           # 14 JPA entities
│       │   │   │   │   ├── Organization.java
│       │   │   │   │   ├── Project.java
│       │   │   │   │   ├── Repository.java
│       │   │   │   │   ├── Pipeline.java
│       │   │   │   │   ├── PipelineVersion.java
│       │   │   │   │   ├── PipelineRun.java
│       │   │   │   │   ├── PipelineStage.java
│       │   │   │   │   ├── PipelineJob.java
│       │   │   │   │   ├── JobAttempt.java
│       │   │   │   │   ├── WebhookEvent.java
│       │   │   │   │   ├── OutboxEvent.java
│       │   │   │   │   ├── Artifact.java
│       │   │   │   │   ├── Deployment.java
│       │   │   │   │   └── AuditEvent.java
│       │   │   │   ├── repository/                       # 14 Spring Data repositories
│       │   │   │   └── service/                          # Domain services (CRUD)
│       │   │   │       ├── OrganizationService.java
│       │   │   │       ├── ProjectService.java
│       │   │   │       ├── RepositoryService.java
│       │   │   │       ├── PipelineService.java
│       │   │   │       ├── ArtifactService.java
│       │   │   │       ├── DeploymentService.java
│       │   │   │       └── AuditService.java
│       │   │   │
│       │   │   ├── pipeline/                             # YAML processing
│       │   │   │   ├── PipelineYamlService.java          # Orchestrates parse → validate → persist
│       │   │   │   ├── PipelineConfigMapper.java         # PipelineConfig → StageDefinition/JobDefinition
│       │   │   │   ├── PipelineValidationException.java  # Validation error exception
│       │   │   │   ├── config/                           # YAML config POJOs
│       │   │   │   │   ├── PipelineRoot.java             # Root YAML element
│       │   │   │   │   ├── PipelineConfig.java
│       │   │   │   │   ├── StageConfig.java              # Stage with name, jobs, dependsOn
│       │   │   │   │   └── JobConfig.java                # Job with name, type, dependsOn
│       │   │   │   ├── parser/
│       │   │   │   │   ├── PipelineYamlParser.java       # SnakeYAML parser
│       │   │   │   │   └── PipelineYamlParseException.java
│       │   │   │   └── validator/
│       │   │   │       ├── SchemaValidator.java           # Structural validation
│       │   │   │       ├── SemanticValidator.java         # Semantic rules
│       │   │   │       ├── DependencyValidator.java       # DFS cycle detection
│       │   │   │       ├── PipelineValidationResult.java
│       │   │   │       └── PipelineValidationFieldError.java
│       │   │   │
│       │   │   ├── execution/                            # Execution engine
│       │   │   │   ├── RunService.java                   # Run creation + queries
│       │   │   │   ├── PipelineOrchestrator.java         # Central coordinator
│       │   │   │   ├── JobDispatcherService.java         # Dependency eval + dispatch
│       │   │   │   ├── StageResultCollector.java         # Status evaluation
│       │   │   │   ├── OutboxEventService.java           # Event persistence
│       │   │   │   ├── OutboxEventPublisher.java         # Scheduled event publishing
│       │   │   │   ├── WebhookEventService.java          # Webhook processing
│       │   │   │   ├── ExecutionContext.java              # Job execution context
│       │   │   │   ├── ExecutionMdc.java                 # MDC logging context
│       │   │   │   ├── StepResult.java                   # Command result record
│       │   │   │   ├── config/
│       │   │   │   │   ├── RabbitMQConfig.java           # Exchange/queue declarations
│       │   │   │   │   ├── WorkspaceConfig.java          # Configurable workspace props
│       │   │   │   │   └── ExecutionConstants.java       # Queue names, defaults
│       │   │   │   ├── message/
│       │   │   │   │   ├── JobDispatchMessage.java       # Dispatch message record
│       │   │   │   │   ├── JobMessageConsumer.java       # RabbitMQ listener (control plane)
│       │   │   │   │   └── JobResultMessage.java         # Result message record
│       │   │   │   └── worker/                           # Embedded worker components
│       │   │   │       ├── WorkerExecutor.java           # Job execution orchestrator
│       │   │   │       ├── StepExecutor.java             # OS command execution
│       │   │   │       ├── GitOperations.java            # Git clone/checkout/verify
│       │   │   │       ├── WorkspaceManager.java         # Workspace creation/cleanup
│       │   │   │       └── ExecutionLogger.java          # Structured job logging
│       │   │   │
│       │   │   └── health/
│       │   │       ├── HealthController.java             # System health endpoint
│       │   │       └── HealthResponse.java
│       │   │
│       │   └── resources/
│       │       ├── application.yml                        # Configuration
│       │       └── db/migration/
│       │           └── V1__create_domain_schema.sql      # 14-table schema
│       │
│       └── test/java/com/cicd/platform/controlplane/    # 30+ test classes
│           ├── YamlPipelineFlowTest.java                 # End-to-end YAML flow
│           ├── EndToEndExecutionTest.java                # Full lifecycle test
│           ├── CreationDispatchFlowTest.java             # Creation → dispatch flow
│           ├── execution/
│           │   ├── PipelineOrchestratorTest.java         # Orchestrator logic
│           │   ├── JobDispatcherServiceTest.java         # Dispatch + dependencies
│           │   ├── RunServiceTest.java                   # Run operations
│           │   ├── StageResultCollectorTest.java         # Status evaluation
│           │   ├── OutboxEventServiceTest.java           # Outbox pattern
│           │   ├── JobMessageConsumerTest.java           # Consumer logic
│           │   ├── RabbitMQIntegrationTest.java          # RabbitMQ integration
│           │   └── worker/                               # Worker component tests
│           ├── pipeline/
│           │   ├── PipelineConfigMapperTest.java         # Mapping logic
│           │   ├── PipelineYamlParserTest.java           # YAML parsing
│           │   └── validator/                            # Validator tests
│           ├── api/                                      # REST API tests
│           │   ├── RunApiTest.java
│           │   ├── PipelineYamlApiTest.java
│           │   └── RunControllerIntegrationTest.java
│           ├── DomainCrudIntegrationTest.java            # Domain CRUD
│           ├── DomainRepositoryIntegrationTest.java      # Repository queries
│           └── domain/service/                           # Service tests
│
├── worker/                               # Separate Worker Service (Java 21)
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/cicd/platform/worker/
│       │   ├── WorkerApplication.java                    # Worker entry point
│       │   ├── config/                                   # Worker configuration
│       │   │   ├── RabbitMQConfig.java                   # Jobs/results/delay/DLQ topology
│       │   │   ├── WorkerProperties.java
│       │   │   ├── SandboxConfig.java
│       │   │   └── JacksonConfig.java
│       │   ├── domain/                                   # Worker domain objects
│       │   │   ├── PipelineJob.java, PipelineResult.java
│       │   │   ├── StageResult.java, StepResult.java
│       │   │   ├── JobStatus.java, JobResult.java
│       │   │   └── ArtifactInfo.java, CommandResult/Status
│       │   ├── messaging/
│       │   │   ├── PipelineJobConsumer.java              # RabbitMQ listener
│       │   │   └── PipelineResultPublisher.java          # Result publisher
│       │   ├── execution/                                # Execution engine
│       │   │   ├── PipelineExecutor.java → StageExecutor → JobExecutor
│       │   │   ├── StepExecutor.java, ArtifactCollector.java
│       │   │   ├── ExecutionContext.java, SandboxEnv.java
│       │   ├── git/
│       │   │   ├── GitService.java (interface)
│       │   │   ├── JGitGitService.java                   # JGit implementation
│       │   │   └── GitCredentialsProvider.java
│       │   ├── pipeline/
│       │   │   ├── PipelineParser.java                   # Worker-side YAML parser
│       │   │   ├── PipelineValidator.java
│       │   │   └── model/                                # StepDefinition, StageDefinition, etc.
│       │   ├── sandbox/
│       │   │   ├── ProcessExecutionSandbox.java          # Process-level sandbox
│       │   │   ├── DockerExecutionSandbox.java           # Docker sandbox
│       │   │   └── StreamCapturer.java
│       │   ├── security/
│       │   │   ├── CommandSecurityPolicy.java            # Command allow/deny
│       │   │   └── SecurityViolationException.java
│       │   ├── service/
│       │   │   ├── PipelineExecutionService.java         # Execution orchestrator
│       │   │   ├── DuplicateJobGuard.java                # In-memory dedup
│       │   │   └── PipelineJobValidator.java
│       │   ├── workspace/
│       │   │   ├── Workspace.java, WorkspaceManager.java
│       │   ├── logging/
│       │   │   ├── ExecutionLogCollector.java, MdcContext.java
│       │   └── observability/
│       │       ├── ExecutionMetrics.java, WorkerHealthIndicator.java
│       └── test/                                         # 15+ test classes
│
├── frontend/                             # React SPA (Phase 0 foundation)
├── infrastructure/                       # Terraform modules (Azure skeleton)
├── docs/                                 # ADRs, module docs, runbooks
├── scripts/
│   └── publish-job.ps1                   # Test job publisher
├── docker-compose.yml                    # Full local stack
├── .env.example                          # Environment variable template
└── README.md                             # This file
```

---

**Last updated**: Based on repository inspection of all backend and worker source files, test files, configuration, and database migrations.
