# CI/CD Automation Platform — Technical Documentation

> **Version:** 0.1.0 · **Status:** Phase 0 + Phase 4 (partial) · **Last updated:** 2026-08-21

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture](#2-architecture)
3. [Pipeline YAML Format](#3-pipeline-yaml-format)
4. [YAML Validation](#4-yaml-validation)
5. [Pipeline Creation](#5-pipeline-creation)
6. [PipelineRun / Stage / Job Lifecycle](#6-pipelinerun--stage--job-lifecycle)
7. [Stage dependsOn](#7-stage-dependson)
8. [Job dependsOn](#8-job-dependson)
9. [JobDispatcher](#9-jobdispatcher)
10. [RabbitMQ Flow](#10-rabbitmq-flow)
11. [Worker Execution](#11-worker-execution)
12. [Retry](#12-retry)
13. [Failure Propagation](#13-failure-propagation)
14. [Cancellation](#14-cancellation)
15. [Outbox Events](#15-outbox-events)
16. [API Endpoints](#16-api-endpoints)
17. [Database Entities](#17-database-entities)
18. [Local Setup](#18-local-setup)
19. [Running Focused Tests](#19-running-focused-tests)
20. [Running the Complete Test Suite](#20-running-the-complete-test-suite)

---

## 1. Project Overview

The CI/CD Automation Platform is an educational DevOps orchestration system that
demonstrates how modern CI/CD pipelines are designed and integrated. It is **not**
a replacement for Jenkins, GitHub Actions, or GitLab CI.

### What it does

- Receives pipeline definitions as YAML
- Parses, validates (schema + semantic + dependency-cycle), and versions them
- Executes multi-stage, multi-job pipelines with dependency ordering
- Provides a REST API for managing organizations, projects, repositories,
  pipelines, runs, artifacts, deployments, and webhooks
- Records every action in an append-only audit trail

### What it does NOT do (future work)

- GitHub/GitLab HMAC webhook signature verification (skeleton only)
- Transactional outbox polling/publishing to downstream consumers
- Docker image build and push to Azure Container Registry
- Azure Container Apps deployment
- Full observability stack (Grafana, Prometheus dashboards, alerting)
- CI workflow automation (the project's own CI/CD)
- Scheduling, RBAC, OIDC-based authentication
- Frontend beyond the health-status SPA shell

---

## 2. Architecture

### High-level

```
┌──────────────┐    webhooks     ┌──────────────────────────────┐
│  Git Provider │ ──────────────▶│  Backend (Control Plane)     │
│  (GitHub/     │                │  Spring Boot 3.3.5 · Java 21 │
│   GitLab)     │                │                              │
└──────────────┘                │  REST API  ·  Orchestration  │
                                │  YAML parsing/validation     │
                                │  Outbox events · Audit       │
                                └───────┬──────────┬───────────┘
                                        │          │
                              dispatch  │          │  results
                                        ▼          ▼
                          ┌──────────────────┐  ┌──────────────┐
                          │     RabbitMQ     │  │  PostgreSQL  │
                          │  3.13-management │  │  16          │
                          └────────┬─────────┘  └──────────────┘
                                   │
                                   ▼
                          ┌──────────────────────────────────┐
                          │  Worker (Execution Engine)        │
                          │  Spring Boot 3.3.5 · Java 21     │
                          │                                  │
                          │  Git clone/checkout               │
                          │  Pipeline parse → validate → run  │
                          │  Process / Docker sandbox         │
                          │  Publish results back to backend  │
                          └──────────────────────────────────┘
```

### Services

| Service | Port | Technology | Role |
|---------|------|-----------|------|
| **backend** | 8081 | Spring Boot 3.3.5, Java 21 | Control plane API, orchestration, outbox, domain logic |
| **worker** | 8082 | Spring Boot 3.3.5, Java 21 | Execution engine: clone, parse YAML, run steps in sandbox, publish results |
| **frontend** | 3000 | React 18, Vite 6, TypeScript | Dashboard SPA (health status only) |
| **postgres** | 5432 | PostgreSQL 16 | Platform state: 14 tables via Flyway |
| **rabbitmq** | 5672 / 15672 | RabbitMQ 3.13 Management | Job dispatch queue + results exchange + DLQ |
| **redis** | 6379 | Redis 7 | Reserved for future caching |
| **keycloak** | 8083 | Keycloak 25.0 | Reserved for future OIDC auth |

### Key design decisions

| Decision | Reference |
|----------|-----------|
| Monorepo, Worker as a separate execution service, pipeline engine inside the Worker, RabbitMQ between job submission and execution | ADR-0001 |
| Transactional outbox pattern for reliable event publishing | `OutboxEventService` + `OutboxEventPublisher` |
| Three-phase YAML validation: schema → semantic → dependency cycle detection | `PipelineYamlService.validate()` |
| Process-based sandbox (default) with optional Docker sandbox | `ProcessExecutionSandbox` / `DockerExecutionSandbox` |
| Command security policy: STRICT / LENIENT / DISABLED modes | `CommandSecurityPolicy` |

---

## 3. Pipeline YAML Format

Pipelines are defined in YAML with a top-level `pipeline` key.

### Structure

```yaml
pipeline:
  name: <string>              # required, max 255 chars
  description: <string>       # optional
  stages:
    - name: <string>          # required, unique across stages
      dependsOn:              # optional, list of stage names
        - <stage-name>
      jobs:
        - name: <string>      # required, unique within the stage
          type: <string>      # required: BUILD | TEST | SCAN | DEPLOY | PACKAGE | CUSTOM
          dependsOn:          # optional, list of job names within the same stage
            - <job-name>
```

### Example

```yaml
pipeline:
  name: build-and-deploy
  description: Build, test, scan, and deploy
  stages:
    - name: build
      jobs:
        - name: compile
          type: BUILD
        - name: package
          type: PACKAGE
          dependsOn:
            - compile

    - name: test
      dependsOn:
        - build
      jobs:
        - name: unit-tests
          type: TEST
        - name: lint
          type: TEST

    - name: scan
      dependsOn:
        - test
      jobs:
        - name: security-scan
          type: SCAN

    - name: deploy
      dependsOn:
        - scan
      jobs:
        - name: deploy-staging
          type: DEPLOY
```

### Valid job types

`BUILD`, `TEST`, `SCAN`, `DEPLOY`, `PACKAGE`, `CUSTOM` — enforced by
`SchemaValidator` at validation time.

### Parsing

Parsed by `PipelineYamlParser` using SnakeYAML into a `PipelineRoot` →
`PipelineConfig` object tree. The YAML must have a top-level `pipeline` key
or parsing fails with `PipelineYamlParseException`.

---

## 4. YAML Validation

Validation is a three-phase pipeline in `PipelineYamlService`:

### Phase 1: Schema validation (`SchemaValidator`)

Checks structural requirements:

- `pipeline.name` is present and ≤ 255 characters
- At least one stage exists
- Each stage has a `name` (≤ 255 chars) and at least one job
- Each job has a `name` (≤ 255 chars) and a `type` from the valid set

### Phase 2: Semantic validation (`SemanticValidator`)

Only runs if schema validation passes. Checks:

- **Stage name uniqueness** — no two stages share the same name (case-insensitive)
- **Job name uniqueness per stage** — no two jobs in the same stage share a name
- **Stage dependency references** — every `dependsOn` entry names a stage that
  actually exists in the pipeline
- **Job dependency references** — every job `dependsOn` entry names a job that
  actually exists within the same stage

### Phase 3: Dependency cycle detection (`DependencyValidator`)

Only runs if schema validation passes. Performs DFS-based cycle detection on:

- **Stage dependency graph** — detects circular `dependsOn` among stages
- **Job dependency graph** (per stage) — detects circular `dependsOn` among
  jobs within each stage

### Error format

All errors are collected into `PipelineValidationResult` as
`PipelineValidationFieldError` records with:

- `field` — JSON-path-like location (e.g., `pipeline.stages[0].jobs[1].type`)
- `code` — error code (`REQUIRED`, `SIZE`, `INVALID`, `DUPLICATE`,
  `INVALID_REFERENCE`, `CYCLIC_DEPENDENCY`)
- `message` — human-readable description

If any validation fails, `PipelineValidationException` is thrown with the
complete error list, and no version is created.

---

## 5. Pipeline Creation

There are two paths to create a pipeline and its first YAML version:

### Path A: Submit YAML to an existing pipeline

```
POST /api/v1/pipelines/{id}/versions
Body: { "yamlContent": "..." }
```

Implemented in `PipelineYamlService.submitYaml()`:

1. Looks up the pipeline by ID
2. Parses and validates the YAML
3. Computes the next version number (max existing version + 1)
4. Persists a new `PipelineVersion` with the YAML content

### Path B: Submit YAML to a project (auto-creates pipeline)

```
POST /api/v1/pipelines/yaml?projectId={projectId}
Body: { "yamlContent": "..." }
```

Implemented in `PipelineYamlService.validateAndSubmitToProject()`:

1. Looks up the project by ID
2. Parses and validates the YAML
3. Finds or creates a `Pipeline` with the pipeline name from the YAML
   (uses `PipelineConfigMapper.toPipeline()`)
4. Creates the versioned `PipelineVersion`

### Pipeline states

A pipeline has status `ACTIVE`, `INACTIVE`, or `ARCHIVED`. Only `ACTIVE`
pipelines can be triggered for a run.

---

## 6. PipelineRun / Stage / Job Lifecycle

### PipelineRun states

```
QUEUED ──▶ RUNNING ──▶ SUCCESS
                   ├──▶ FAILED
                   └──▶ CANCELLED
```

Implemented in `PipelineRun.RunStatus`: `QUEUED`, `RUNNING`, `SUCCESS`,
`FAILED`, `CANCELLED`.

### PipelineStage states

```
PENDING ──▶ RUNNING ──▶ SUCCESS
                   ├──▶ FAILED
                   └──▶ SKIPPED
```

Implemented in `PipelineStage.StageStatus`: `PENDING`, `RUNNING`, `SUCCESS`,
`FAILED`, `SKIPPED`.

### PipelineJob states

```
PENDING ──▶ QUEUED ──▶ RUNNING ──▶ SUCCESS
                                   ├──▶ FAILED
                                   └──▶ CANCELLED
```

Implemented in `PipelineJob.JobStatus`: `PENDING`, `QUEUED`, `RUNNING`,
`SUCCESS`, `FAILED`, `CANCELLED`.

### JobAttempt states

```
PENDING ──▶ RUNNING ──▶ SUCCESS
                   ├──▶ FAILED
                   └──▶ CANCELLED
```

Implemented in `JobAttempt.AttemptStatus`: `PENDING`, `RUNNING`, `SUCCESS`,
`FAILED`, `CANCELLED`.

### Full execution flow

1. `RunService.triggerRun()` creates a `PipelineRun` with status `QUEUED`
2. `PipelineOrchestrator.startExecution()` is called:
   - Parses the pipeline YAML from the run's `PipelineVersion`
   - Creates `PipelineStage` entities (status `PENDING`, ordered by index)
   - Creates `PipelineJob` entities within each stage (status `PENDING`)
   - Sets run status to `RUNNING` and records `startedAt`
   - Publishes `RUN_STARTED` outbox event
   - Calls `JobDispatcherService.dispatchReadyJobs()`
3. Dispatcher finds PENDING jobs whose dependencies are met, transitions
   them to `QUEUED`, creates `JobAttempt`, and sends `JobDispatchMessage`
   to RabbitMQ
4. Worker (`PipelineJobConsumer`) receives the message, executes the job,
   and publishes a result
5. Backend `PipelineOrchestrator.handleJobCompletion()` updates job/attempt
   status, evaluates stage completion, evaluates run completion, and
   dispatches newly-ready jobs

---

## 7. Stage dependsOn

Stages can declare dependencies on other stages using `dependsOn`:

```yaml
stages:
  - name: test
    dependsOn:
      - build
  - name: deploy
    dependsOn:
      - test
```

### Resolution logic (`JobDispatcherService.dispatchReadyJobs()`)

For each stage that is not `SUCCESS` or `SKIPPED`:

1. **If `dependsOn` is declared**: only dispatch when all named dependency
   stages have status `SUCCESS` (`areDeclaredDepsMet()`)
2. **If `dependsOn` is not declared** (positional fallback): dispatch only
   when all stages with a lower `orderIndex` have status `SUCCESS`
   (`areAllPreviousStagesSuccess()`)

If the dependency check fails, the stage is skipped for this dispatch cycle
and will be re-evaluated after the next job completion.

### Validation

- `SemanticValidator.validateStageDependenciesExist()` ensures every
  `dependsOn` entry names an existing stage
- `DependencyValidator.detectStageCycles()` ensures no circular dependencies

---

## 8. Job dependsOn

Jobs within a stage can declare dependencies on other jobs in the same stage:

```yaml
stages:
  - name: build
    jobs:
      - name: compile
        type: BUILD
      - name: package
        type: PACKAGE
        dependsOn:
          - compile
```

### Resolution logic (`JobDispatcherService.dispatchReadyJobs()`)

For each PENDING job within an eligible stage:

1. **If `dependsOn` is declared**: only dispatch when all named dependency
   jobs have status `SUCCESS` (`areJobDepsMet()`)
2. **If `dependsOn` is not declared**: dispatch immediately (no inter-job
   dependency)

All inter-job dependencies are scoped to the same stage; cross-stage job
dependencies are not supported.

### Validation

- `SemanticValidator.validateJobDependenciesExist()` ensures every job
  `dependsOn` entry names a job that exists within the same stage
- `DependencyValidator.detectJobCycles()` ensures no circular dependencies
  within any stage's job graph

---

## 9. JobDispatcher

`JobDispatcherService` is the component responsible for moving jobs from
`PENDING` to `QUEUED` and sending them to RabbitMQ.

### `dispatchReadyJobs(runId)`

Called after run start and after each job completion. For each stage in
order:

1. Skip stages that are `SUCCESS` or `SKIPPED`
2. Check stage dependencies (see [Stage dependsOn](#7-stage-dependson))
3. Skip stages with status `FAILED`
4. For each PENDING job in the stage, check job dependencies (see
   [Job dependsOn](#8-job-dependson))
5. Call `dispatchJob()` for eligible jobs

### `dispatchJob(job)`

1. Set job status to `QUEUED`
2. Compute `attemptNumber` (max existing + 1, or 1 if none)
3. Create a `JobAttempt` with status `PENDING`
4. Build a `JobDispatchMessage` with job metadata (id, runId, versionId,
   name, type, gitUrl, branch, commitSha, attemptNumber)
5. Send to `pipeline-jobs-exchange` with routing key `job-dispatch`

### `dispatchForRetry(job, attemptNumber)`

Used when a job fails and retry is enabled. Same as `dispatchJob` but uses
the provided `attemptNumber` instead of computing it.

---

## 10. RabbitMQ Flow

### Backend topology

| Exchange | Type | Queue | Routing Key |
|----------|------|-------|-------------|
| `pipeline-jobs-exchange` | direct | `pipeline-jobs` | `job-dispatch` |
| `pipeline-job-results-exchange` | direct | `pipeline-job-results` | `job-result` |
| `outbox.exchange` | direct | `outbox.queue` | `outbox.event` |

Defined in `backend/.../execution/config/RabbitMQConfig.java`.

### Worker topology

| Exchange | Type | Queue | Notes |
|----------|------|-------|-------|
| `cicd.jobs.exchange` | direct | `cicd.jobs` | DLX → dead routing key |
| `cicd.jobs.exchange` | direct | `cicd.jobs.delay` | TTL delay queue, DLX → job routing key |
| `cicd.jobs.exchange` | direct | `cicd.jobs.dlq` | Dead-letter queue |
| `cicd.results.exchange` | direct | `cicd.results` | Results from worker → backend |

Defined in `worker/.../config/RabbitMQConfig.java`.

### Message flow

```
Backend                          RabbitMQ                        Worker
───────                          ────────                        ──────
JobDispatcherService
  └─▶ convertAndSend ──────────▶ pipeline-jobs queue ──────────▶ PipelineJobConsumer
       (JobDispatchMessage)                                        │
                                                                   ▼
                                                            PipelineExecutionService
                                                                   │
                                                            PipelineResultPublisher
                                                                   │
  JobMessageConsumer ◀────── pipeline-job-results ◀───────────────┘
  (backend, in-process)        (PipelineResult)
```

**Note:** The backend has two execution paths:
- **In-process** (`JobMessageConsumer`): the backend itself consumes from
  `pipeline-jobs` queue and runs `WorkerExecutor` locally. This is the
  path used by the integration tests.
- **Worker** (`PipelineJobConsumer`): the separate worker process consumes
  from `cicd.jobs` queue, clones the repo, and runs the full pipeline.

The worker's consumer uses **manual ACK** mode. Messages are acknowledged
only after successful processing. On failure, messages are routed to the
delay queue for retry or to the DLQ.

### Message formats

**`JobDispatchMessage`** (backend → queue):
```java
record JobDispatchMessage(
    UUID jobId, UUID runId, UUID pipelineVersionId,
    String jobName, String jobType, String gitUrl,
    String branch, String commitSha, int attemptNumber,
    int version, UUID correlationId
)
```

**`JobResultMessage`** (backend receives):
```java
record JobResultMessage(
    UUID jobId, UUID runId, UUID attemptId, int attemptNumber,
    boolean success, int exitCode, String workerId,
    String errorMessage, Instant startedAt, Instant finishedAt
)
```

**Worker `PipelineJob`** (worker consumes):
```java
record PipelineJob(
    String jobId, String pipelineId, String repositoryUrl,
    String commitSha, String branch, String pipelineFile,
    String environment, Map<String, String> metadata
)
```

**Worker `PipelineResult`** (worker publishes):
```java
record PipelineResult(
    String jobId, String pipelineId, JobStatus status,
    String workerId, String repositoryUrl, String commitSha,
    String branch, Instant startedAt, Instant completedAt,
    long durationMs, List<StageResult> stages, String message
)
```

---

## 11. Worker Execution

The worker's execution lifecycle is orchestrated by `PipelineExecutionService`:

### Lifecycle

```
1. Validate incoming job message (PipelineJobValidator)
2. Acquire duplicate-job guard (DuplicateJobGuard)
3. Create workspace (WorkspaceManager)
4. Git clone + detached checkout at commit SHA (GitService / JGitGitService)
5. Locate pipeline YAML in repo (PipelineLoader)
6. Parse YAML (PipelineParser)
7. Validate pipeline definition (PipelineValidator)
8. Execute pipeline (PipelineExecutor)
9. Publish result (PipelineResultPublisher)
10. Cleanup workspace (WorkspaceManager)
```

### PipelineExecutor

Runs stages **sequentially**. For each stage:
- If context is cancelled → mark stage as cancelled, continue to next
- Execute stage via `StageExecutor`
- If stage fails/times out/is cancelled → mark remaining stages as
  cancelled, stop execution

### StageExecutor

Runs all jobs within a stage.

### JobExecutor

Executes individual jobs by running steps.

### StepExecutor

Runs individual commands via the configured sandbox.

### Sandbox implementations

| Class | Description |
|-------|------------|
| `ProcessExecutionSandbox` | Default. Runs commands as local OS processes |
| `DockerExecutionSandbox` | Runs commands in Docker containers with security hardening (`--cap-drop ALL`, `no-new-privileges`) |

### Security (`CommandSecurityPolicy`)

Three modes: `STRICT`, `LENIENT`, `DISABLED`. In `STRICT` mode (default),
blocks catastrophic commands (`rm -rf /`, `mkfs`, `dd`, `chmod 777`, etc.)
and credential-exfiltration patterns.

### Watchdog

A scheduled executor monitors pipeline duration. If execution exceeds
`worker.max-pipeline-duration-ms`, the context is cancelled.

---

## 12. Retry

Retry operates at two levels:

### Backend retry (orchestrated by `PipelineOrchestrator`)

In `PipelineOrchestrator.handleJobCompletion()`, after a failed job:

1. Check if `workspace.retryEnabled` is `true`
2. Count existing attempts for the job
3. If `nextAttempt < maxRetries` (default 3), call
   `JobDispatcherService.dispatchForRetry()`
4. The job is re-queued and re-dispatched to RabbitMQ with a new
   `JobAttempt`

### Worker retry (RabbitMQ-level infrastructure retry)

In `PipelineJobConsumer.onMessage()`, if a `WorkerException` occurs
(infrastructure failure like git clone failure, workspace error):

1. Read `x-retry-count` header from the message
2. If `retryCount < maxRetries` (default 3), publish to the delay queue
   via `resultPublisher.publishRetry()`
3. The delay queue uses `x-message-ttl` to delay re-delivery
4. If retries are exhausted, mark the job as permanently failed and
   `basicReject` to the DLQ

### Configuration

Backend:
```yaml
execution.workspace:
  retry-enabled: true
  max-retries: 3
```

Worker:
```yaml
worker:
  retry:
    enabled: true
    max-retries: 3
```

---

## 13. Failure Propagation

### Job failure → Stage status

`StageResultCollector.evaluateStageStatus()`:
- All jobs `SUCCESS` → stage `SUCCESS`
- Any job `FAILED` → stage `FAILED`
- All jobs `CANCELLED` → stage `FAILED`
- Otherwise → stage `RUNNING`

### Stage failure → Run status

`StageResultCollector.evaluateRunStatus()`:
- All stages `SUCCESS` → run `SUCCESS`
- Any stage `FAILED` → run `FAILED`
- Otherwise → run `RUNNING`

### Stage failure → Downstream stages

When `JobDispatcherService.dispatchReadyJobs()` is called after a job
completion, stages with status `FAILED` are skipped. Stages that depend
on the failed stage (via `dependsOn`) will never have their dependencies
met, so they remain `PENDING` and are never dispatched.

### Pipeline failure (worker side)

`PipelineExecutor.execute()` stops at the first failed stage and marks
all remaining stages as `CANCELLED` with a reason message.

---

## 14. Cancellation

### Trigger

```
POST /api/v1/runs/{id}/cancel
```

Handled by `RunService.cancelRun()` → `PipelineOrchestrator.cancelRun()`.

### Backend cancellation logic (`PipelineOrchestrator.cancelRun()`)

1. Set run status to `CANCELLED` and record `finishedAt`
2. For each stage:
   - For each job in `PENDING` or `QUEUED` status:
     - Set job status to `CANCELLED`
     - Set any `PENDING` or `RUNNING` attempts to `CANCELLED`
   - For stages in `PENDING` status: set to `SKIPPED`
3. Publish `RUN_CANCELLED` outbox event

### Consumer-level cancellation guard

`JobMessageConsumer.onJobDispatch()` checks the run status before
executing. If the run is `CANCELLED`, the message is ACK'd without
execution.

### Worker-side cancellation

`PipelineExecutor.execute()` checks `ctx.isCancelled()` before each
stage. If cancelled, the remaining stages are marked as `CANCELLED`
in the result.

A scheduled watchdog in `PipelineExecutionService` cancels execution
if the pipeline exceeds its maximum duration.

---

## 15. Outbox Events

### Pattern

The platform uses the **transactional outbox pattern** to reliably publish
domain events. Events are persisted to the `outbox_events` table within
the same transaction as the domain mutation, then asynchronously published
to RabbitMQ by a scheduled poller.

### Event types emitted

| Event Type | Aggregate Type | Triggered When |
|------------|---------------|----------------|
| `RUN_STARTED` | `PipelineRun` | Run execution begins |
| `JOB_COMPLETED` | `PipelineJob` | Job finishes (success or failure) |
| `STAGE_COMPLETED` | `PipelineStage` | All jobs in a stage complete |
| `RUN_COMPLETED` | `PipelineRun` | All stages complete |
| `RUN_CANCELLED` | `PipelineRun` | Run is cancelled |

### Implementation

**`OutboxEventService`** (`backend/.../execution/OutboxEventService.java`):
- `publishEvent()` — serializes payload to JSON, creates `OutboxEvent`
  with status `PENDING`, saves within the caller's `@Transactional`
- `markPublished()` — sets status to `PUBLISHED` with timestamp
- `markFailed()` — sets status to `FAILED` with error message

**`OutboxEventPublisher`** (`backend/.../execution/OutboxEventPublisher.java`):
- `@Scheduled(fixedDelay = "${app.outbox.poll-interval-ms:5000}")` polls
  for `PENDING` events
- For each event, publishes to `outbox.exchange` with routing key
  `outbox.event`, setting `eventType`, `aggregateType`, and `aggregateId`
  as message headers
- On success: `markPublished()`. On failure: `markFailed()`

### Status lifecycle

```
PENDING ──▶ PUBLISHED
         └──▶ FAILED (retryable on next poll)
```

**Current status:** Outbox events are persisted and polled, but no
downstream consumers are wired to the `outbox.queue` yet. This is
future work.

---

## 16. API Endpoints

All endpoints are prefixed with `/api/v1`. OpenAPI docs are available at
`/swagger-ui.html` and the spec at `/api-docs`.

### Health

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/health` | Checks DB + RabbitMQ connectivity |

### Organizations

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/organizations` | Create (201) |
| `GET` | `/api/v1/organizations` | List all |
| `GET` | `/api/v1/organizations/{id}` | Get by ID |
| `PUT` | `/api/v1/organizations/{id}` | Update |
| `DELETE` | `/api/v1/organizations/{id}` | Delete (204) |

### Projects

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/projects` | Create (201), requires `organizationId` |
| `GET` | `/api/v1/projects?organizationId=` | List by org |
| `GET` | `/api/v1/projects/{id}` | Get by ID |
| `PUT` | `/api/v1/projects/{id}` | Update |
| `DELETE` | `/api/v1/projects/{id}` | Delete (204) |

### Repositories

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/repositories` | Create (201) |
| `GET` | `/api/v1/repositories?projectId=` | List by project |
| `GET` | `/api/v1/repositories/{id}` | Get by ID |
| `PUT` | `/api/v1/repositories/{id}` | Update |
| `DELETE` | `/api/v1/repositories/{id}` | Delete (204) |

### Pipelines

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/pipelines` | Create (201) |
| `GET` | `/api/v1/pipelines?projectId=` | List by project |
| `GET` | `/api/v1/pipelines/{id}` | Get by ID |
| `PUT` | `/api/v1/pipelines/{id}` | Update |
| `DELETE` | `/api/v1/pipelines/{id}` | Delete (204) |
| `GET` | `/api/v1/pipelines/{id}/versions` | List versions |
| `POST` | `/api/v1/pipelines/{id}/versions` | Submit YAML version (201) |
| `POST` | `/api/v1/pipelines/yaml?projectId=` | Submit YAML to project (201) |

### Runs

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/runs` | Trigger pipeline run (201) |
| `GET` | `/api/v1/runs?versionId=` | List runs by version |
| `GET` | `/api/v1/runs/{id}` | Get run details |
| `POST` | `/api/v1/runs/{id}/cancel` | Cancel run |
| `GET` | `/api/v1/runs/{id}/stages` | Get stages for a run |
| `GET` | `/api/v1/runs/{runId}/stages/{stageId}/jobs` | Get jobs for a stage |
| `GET` | `/api/v1/runs/{runId}/stages/{stageId}/jobs/{jobId}/attempts` | Get attempts for a job |

### Webhooks

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/webhooks/{provider}` | Receive webhook (201) |
| `GET` | `/api/v1/webhooks/{id}` | Get webhook event |

### Deployments

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/deployments?pipelineRunId=&environment=` | Create (201) |
| `GET` | `/api/v1/deployments` | List (filter by runId or env) |
| `GET` | `/api/v1/deployments/{id}` | Get by ID |
| `POST` | `/api/v1/deployments/{id}/start` | Start deployment |
| `POST` | `/api/v1/deployments/{id}/complete?success=&endpoint=` | Complete deployment |
| `DELETE` | `/api/v1/deployments/{id}` | Delete (204) |

### Artifacts

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/artifacts?pipelineRunId=&artifactType=&name=&locationUrl=&jobId=` | Create (201) |
| `GET` | `/api/v1/artifacts?pipelineRunId=` | List by run |
| `GET` | `/api/v1/artifacts/{id}` | Get by ID |
| `DELETE` | `/api/v1/artifacts/{id}` | Delete (204) |

---

## 17. Database Entities

14 tables defined in `V1__create_domain_schema.sql`. All use UUID primary
keys and `created_at`/`updated_at` timestamps.

### Core domain hierarchy

```
Organization
  └─▶ Project
        └─▶ Repository
        └─▶ Pipeline
              └─▶ PipelineVersion (immutable YAML snapshots)
                    └─▶ PipelineRun (execution instances)
                          └─▶ PipelineStage (ordered stages)
                          │     └─▶ PipelineJob
                          │           └─▶ JobAttempt
                          └─▶ Artifact
                          └─▶ Deployment
```

### Entity summary

| Entity | Table | Key Fields | Status Values |
|--------|-------|-----------|---------------|
| `Organization` | `organizations` | name, slug (unique), description | `ACTIVE`, `INACTIVE`, `SUSPENDED` |
| `Project` | `projects` | org FK, name, slug; unique(org, slug) | `ACTIVE`, `ARCHIVED`, `SUSPENDED` |
| `Repository` | `repositories` | project FK, provider, URL, defaultBranch | `ACTIVE`, `INACTIVE`, `PENDING` |
| `Pipeline` | `pipelines` | project FK, name; unique(project, name) | `ACTIVE`, `INACTIVE`, `ARCHIVED` |
| `PipelineVersion` | `pipeline_versions` | pipeline FK, version (int), yamlContent (TEXT) | — |
| `PipelineRun` | `pipeline_runs` | version FK, repo FK, commitSha, branch, triggerType | `QUEUED`, `RUNNING`, `SUCCESS`, `FAILED`, `CANCELLED` |
| `PipelineStage` | `pipeline_stages` | run FK, name, orderIndex; unique(run, order) | `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `SKIPPED` |
| `PipelineJob` | `pipeline_jobs` | stage FK, name, jobType, workerId, exitCode | `PENDING`, `QUEUED`, `RUNNING`, `SUCCESS`, `FAILED`, `CANCELLED` |
| `JobAttempt` | `job_attempts` | job FK, attemptNumber; unique(job, attempt) | `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `CANCELLED` |
| `WebhookEvent` | `webhook_events` | provider, deliveryId (unique pair), payload (JSONB) | `RECEIVED`, `PROCESSING`, `PROCESSED`, `REJECTED`, `FAILED` |
| `OutboxEvent` | `outbox_events` | eventType, aggregateType, aggregateId, payload (JSONB) | `PENDING`, `PUBLISHED`, `FAILED` |
| `Artifact` | `artifacts` | run FK, job FK, artifactType, name, locationUrl | — |
| `Deployment` | `deployments` | run FK, environment, imageDigest, endpoint | `PENDING`, `DEPLOYING`, `SUCCESS`, `FAILED`, `ROLLED_BACK` |
| `AuditEvent` | `audit_events` | actor, action, resourceType, resourceId, correlationId | — |

### Relationships

- `Project` → `Organization` (many-to-one, `ON DELETE RESTRICT`)
- `Repository` → `Project` (many-to-one, `RESTRICT`)
- `Pipeline` → `Project` (many-to-one, `RESTRICT`)
- `PipelineVersion` → `Pipeline` (many-to-one, `RESTRICT`)
- `PipelineRun` → `PipelineVersion` (many-to-one, `RESTRICT`)
- `PipelineRun` → `Repository` (many-to-one, `SET NULL`)
- `PipelineStage` → `PipelineRun` (many-to-one, `RESTRICT`)
- `PipelineJob` → `PipelineStage` (many-to-one, `RESTRICT`)
- `JobAttempt` → `PipelineJob` (many-to-one, `RESTRICT`)
- `WebhookEvent` → `Repository` (many-to-one, `SET NULL`)
- `Artifact` → `PipelineRun` (many-to-one, `RESTRICT`)
- `Artifact` → `PipelineJob` (many-to-one, `SET NULL`)
- `Deployment` → `PipelineRun` (many-to-one, `RESTRICT`)

---

## 18. Local Setup

### Prerequisites

- **Docker** and **Docker Compose** (v2)
- **Java 21** (for running services outside Docker)
- **Maven 3.9+** (for building)
- **Node.js 20+** (for frontend, outside Docker)

### Quick start with Docker Compose

```bash
cd DevOps-CI-CD-Automation-Platform
docker compose up --build
```

This starts all 7 services on the `cicd-platform-local` network:

| Service | URL |
|---------|-----|
| Frontend | http://localhost:3000 |
| Backend API | http://localhost:8081 |
| Worker | http://localhost:8080 |
| RabbitMQ Management | http://localhost:15672 (guest/guest) |
| PostgreSQL | localhost:5432 (cicd/cicd/cicd) |
| Keycloak | http://localhost:8083 (admin/admin) |
| Redis | localhost:6379 |

### Health check

```bash
curl http://localhost:8081/api/v1/health
```

### Running backend outside Docker

```bash
cd backend
# Ensure PostgreSQL and RabbitMQ are running (via docker compose)
mvn spring-boot:run
```

### Running worker outside Docker

```bash
cd worker
mvn spring-boot:run
```

### Environment variables

Defined in `.env` (copy from `.env.example`):

```
FRONTEND_PORT=3000
BACKEND_PORT=8081
WORKER_PORT=8082
RABBITMQ_DEFAULT_USER=guest
RABBITMQ_DEFAULT_PASS=guest
POSTGRES_DB=cicd
POSTGRES_USER=cicd
POSTGRES_PASSWORD=cicd
```

---

## 19. Running Focused Tests

### Backend

```bash
cd backend

# Run a single test class
mvn test -Dtest=PipelineYamlServiceTest

# Run a single test method
mvn test -Dtest=PipelineYamlServiceTest#submitYamlCreatesVersion

# Run all unit tests in a package
mvn test -Dtest="com.cicd.platform.controlplane.pipeline.**"

# Run integration tests (requires H2, auto-configured in test profile)
mvn test -Dtest=RunApiTest
```

Test profile (`application-test.yml`) uses H2 in-memory database with
`ddl-auto: create-drop` and disables RabbitMQ listener auto-startup.

### Worker

```bash
cd worker

# Run a single test class
mvn test -Dtest=PipelineExecutorTest

# Run a single test method
mvn test -Dtest=PipelineExecutorTest#allStagesSuccess

# Run sandbox/security unit tests
mvn test -Dtest="com.cicd.platform.worker.sandbox.**"
mvn test -Dtest=CommandSecurityPolicyTest

# Run integration tests (require Testcontainers: Docker)
mvn verify -Dtest=none -Dit.test=RabbitMqFlowIT
mvn verify -Dtest=none -Dit.test=CommandTimeoutIT
```

**Note:** Worker integration tests (`*IT.java`) require Docker running
for Testcontainers (RabbitMQ container). They are excluded from the
default `mvn test` via surefire configuration and run separately via
failsafe during `mvn verify`.

---

## 20. Running the Complete Test Suite

### Backend (all tests)

```bash
cd backend
mvn test
```

Runs 32 test files: unit tests for services, execution pipeline, YAML
parsing/validation, health, and integration tests using H2 + MockMvc.

### Worker (all tests)

```bash
cd worker

# Unit tests only
mvn test

# Unit + integration tests
mvn verify
```

Runs 15 test files. `mvn test` runs unit tests; `mvn verify` additionally
runs integration tests via failsafe (requires Docker for Testcontainers).

### Full stack validation

```bash
# From project root
docker compose up -d postgres rabbitmq
cd backend && mvn test && cd ..
cd worker && mvn verify && cd ..
```

### Test infrastructure

| Framework | Backend | Worker |
|-----------|---------|--------|
| JUnit 5 | Yes | Yes |
| Mockito | Yes | Yes |
| Spring Boot Test | Yes | Yes |
| MockMvc | Yes | No |
| H2 in-memory | Yes (test profile) | No |
| Testcontainers | No | Yes (RabbitMQ) |
| Awaitility | No | Yes |

---

## Implementation Status Summary

| Area | Status | Details |
|------|--------|---------|
| Backend REST API (CRUD) | Implemented | 9 controllers, 40+ endpoints |
| Pipeline YAML parsing | Implemented | SnakeYAML → `PipelineConfig` objects |
| 3-phase YAML validation | Implemented | Schema + semantic + dependency cycle |
| Pipeline versioning | Implemented | Immutable YAML versions, auto-numbered |
| Run triggering | Implemented | API-triggered runs via `RunService` |
| Stage/Job creation | Implemented | Derived from YAML at run start |
| Job dispatching | Implemented | Dependency-aware, via RabbitMQ |
| Orchestration | Implemented | Sequential stage dispatch, completion evaluation |
| Retry (backend) | Implemented | Configurable max retries, re-dispatch |
| Retry (worker) | Implemented | RabbitMQ delay queue + DLQ |
| Cancellation | Implemented | API-triggered, consumer-guarded, worker-aware |
| Outbox events | Implemented (write side) | Persisted, polled, published to RabbitMQ |
| Outbox consumers | Not implemented | No downstream services consume outbox events yet |
| Worker execution engine | Implemented | Full pipeline → stage → job → step execution |
| Process sandbox | Implemented | Default local process execution |
| Docker sandbox | Implemented | Container-based execution with security hardening |
| Command security | Implemented | STRICT/LENIENT/DISABLED modes |
| Git operations | Implemented | JGit clone, fetch, detached checkout |
| Webhook receiving | Implemented | GitHub/GitLab, idempotent, HMAC skeleton |
| Webhook HMAC verification | Not implemented | Constant-time compare is wired but secrets are not configured |
| Frontend | Phase 0 shell | Health dashboard only, no navigation |
| Azure infrastructure | Skeleton | Terraform resource group + placeholders |
| Observability | Partial | Actuator health + Prometheus metrics (worker) |
| Audit trail | Implemented | Append-only `audit_events` table, `AuditService` |
| CI/CD for this project | Not implemented | No GitHub Actions / pipeline workflows |
