# Module: Worker (execution engine)

- **Status:** Implemented (Phase 4 engine)
- **Location:** `worker/`
- **Build:** Spring Boot 3.3.5, Java 21, JGit, Testcontainers (failsafe `*IT`)
- **Source of truth for details:** `worker/README.md`

## 1. Purpose and responsibility

The Worker is the platform's **data plane**. It consumes `PipelineJob`
messages from RabbitMQ, clones the referenced repository, checks out the exact
commit, loads and parses the pipeline YAML, validates it, and executes its
steps inside a sandboxed workspace. The outcome is published as a structured
`PipelineResult` back to RabbitMQ.

It owns exactly one bounded capability: **executing a pinned pipeline job in
isolation and reporting the result.** It has no control-plane API, no database
access, and no direct cloud deployment capability.

## 2. Architecture / dependencies

```text
RabbitMQ (cicd.jobs.exchange)
   │  cicd.job.submitted
   ▼
PipelineJobConsumer (manual ACK)
   │
   ▼
PipelineJobValidator ──► DuplicateJobGuard (in-memory dedup)
   │
   ▼
PipelineExecutionService
   ├─ WorkspaceManager / Workspace        (per-job work dir)
   ├─ JGitGitService  (GitService)        (clone + SHA verify + detached checkout)
   ├─ PipelineLoader / PipelineParser / PipelineValidator
   └─ PipelineExecutor (Stage/Job/Step executors, ArtifactCollector, watchdog)
   │
   ▼
PipelineResultPublisher ──► RabbitMQ (cicd.results.exchange)
```

Trust boundary: repository code and pipeline YAML are untrusted. All commands
run in a child process or container via the sandbox abstraction — never inside
the JVM or the control plane. The worker holds no database credentials.

Dependencies (`worker/pom.xml`): spring-boot-starter-web, starter-amqp,
starter-actuator, starter-validation, jackson-dataformat-yaml, JGit,
micrometer-registry-prometheus; test scope: spring-boot-starter-test,
spring-rabbit-test, testcontainers, awaitility.

## 3. Data model

- `domain/` — `PipelineJob` (jobId, pipelineId, repositoryUrl, commitSha,
  branch, pipelineFile, environment, metadata, createdAt), `PipelineResult`
  (jobId, pipelineId, status, workerId, redacted URL, commitSha, branch,
  started/completed timestamps, duration, stages, message), plus `StageResult`,
  `JobResult`, `StepResult`, `ArtifactInfo`, `CommandResult`/`CommandStatus`,
  `JobStatus`.
- No persistence. The worker stores nothing durable; state lives in RabbitMQ
  messages and in the eventual control-plane database (later phases).

## 4. API / event contract

Inbound event (`PipelineJob`, JSON on `cicd.jobs.exchange` → `cicd.jobs`):

- Routing key: `cicd.job.submitted`
- Required fields: `jobId`, `pipelineId`, `repositoryUrl`, `commitSha`.
- Optional: `branch`, `pipelineFile` (default `pipeline.yml`), `environment`,
  `metadata`.

Outbound event (`PipelineResult`, JSON on `cicd.results.exchange`):

- Routing key: `cicd.result`
- Headers: `jobId`, `pipelineId`, `status`, `workerId`.
- Always published for workload outcomes (build/test failures included);
  infrastructure failures are retried first (see §9).

RabbitMQ topology is declared in
`config/RabbitMQConfig.java`:

- Exchanges: `cicd.jobs.exchange`, `cicd.results.exchange` (direct, durable).
- Queues: `cicd.jobs`, `cicd.jobs.delay` (TTL = retry delay, DLX → jobs
  exchange), `cicd.jobs.dlq`.
- Routing keys: `cicd.job.submitted`, `cicd.job.delay`, `cicd.job.dead`,
  `cicd.result`.

## 5. Implementation

### Job consumption

`messaging/PipelineJobConsumer` listens on `${worker.rabbit.job-queue}` with
**manual acknowledgement**:

- A job is acknowledged **only after** a result was published or the message
  was routed to retry/DLQ.
- Malformed JSON → `basicReject(false)` into the DLQ.
- Validation failure → a FAILED `PipelineResult` is published, then reject.
- `DuplicateJobGuard.tryAcquire(jobId)` prevents double execution of the same
  job within the process (RabbitMQ is at-least-once).

### Git checkout

`git/JGitGitService` (implementing `GitService`):

- Clones the repository (all branches, no initial checkout), verifies the
  requested SHA exists, performs a **detached checkout**, and verifies HEAD.
- Credentials come from `GitCredentialsProvider`
  (`GIT_USERNAME`/`GIT_PASSWORD`/`GIT_TOKEN`), empty by default.
- Returns `CommitInfo` (commit SHA + branch).

### Pipeline parsing and validation

`pipeline/PipelineLoader`, `PipelineParser`, `PipelineValidator`:

- `PipelineLoader` locates the pipeline file inside the checked-out repo.
- `PipelineParser` deserializes the YAML into
  `pipeline/model/{PipelineDefinition, StageDefinition, JobDefinition,
  StepDefinition, StepType}` via Jackson YAML.
- `PipelineValidator` enforces schema and security rules: stage/job/step
  names, working directories (must not escape repo root), environment variable
  names/values (secret-named variables are blocked), step limits, and the
  supported action allowlist.

### Pipeline execution

`execution/PipelineExecutor` drives `StageExecutor` → `JobExecutor` →
`StepExecutor`:

- Steps run in order; the first non-zero exit stops the job.
- `ExecutionContext` carries the job, workspace, and `ExecutionLogCollector`.
- `ArtifactCollector` captures declared artifact paths; `SandboxEnv` builds the
  whitelisted environment.
- A single-threaded watchdog (`pipeline-watchdog`) cancels a job that exceeds
  `worker.max-pipeline-duration-ms`.
- `buildImage` steps (`docker build`) are disabled by default
  (`WORKER_BUILD_IMAGE_ENABLED=false`).

### Command execution

`command/CommandExecutor` runs each step through the active sandbox with a
per-command timeout (`worker.command-timeout-ms`). Timeouts surface as
`CommandTimeoutException`; failures as `CommandExecutionException`. Results are
recorded as `CommandResult` with status.

## 6. Sandbox / security model

- `sandbox/ExecutionSandbox` abstraction with two implementations:
  - `ProcessExecutionSandbox` (default, local dev): child process, passes a
    **whitelisted** environment (safe base set + job env) — no host env leak.
  - `DockerExecutionSandbox`: per-step containers with
    `--cap-drop ALL --security-opt no-new-privileges --rm`, no docker socket,
    arg-list invocation (no host shell).
- `security/CommandSecurityPolicy` (STRICT default, RELAXED optional) blocks
  destructive/exfiltration commands and secret-named environment variables;
  violations raise `SecurityViolationException`.
- Pipeline YAML and commands are validated before execution; `workingDirectory`
  may not escape the repo root.
- Logs are captured via `logging/ExecutionLogCollector` and `StreamCapturer`;
  the worker redacts credentials from URLs and never logs repository
  credentials. `MdcContext` threads `workerId`/`jobId`/`stage`/`job`/`step`
  through SLF4J MDC.

## 7. Testing

- Unit tests (surefire): parser, validator, job validator, duplicate guard,
  command security policy, process sandbox, workspace manager, step/job/pipeline
  executors, JGit service (with a local test repo fixture).
- Integration tests (failsafe `*IT`, Testcontainers): `RabbitMqFlowIT`
  (end-to-end RabbitMQ job → result), `CommandTimeoutIT`.
- Build: `mvn -B verify` (integration tests require Docker).

## 8. Monitoring

- `observability/ExecutionMetrics` exposes Prometheus counters (jobs started/
  finished, malformed, validation failures, infrastructure failures) via
  `/actuator/prometheus`.
- `observability/WorkerHealthIndicator` reports worker status (active jobs from
  the duplicate guard) via `/actuator/health`.
- Log pattern includes MDC context (`workerId`, `jobId`, `stage`, `job`,
  `step`) for correlation.

## 9. Failure handling

- **Workload failure** (build/test/config): FAILED `PipelineResult` published;
  no automatic retry.
- **Infrastructure failure** (git, workspace, sandbox, command infra):
  `WorkerException` classified by the consumer; retried through the delay
  queue with `x-retry-count` header up to `worker.max-retries`
  (default 3, delay `worker.retry-delay-ms`), then permanently failed to DLQ
  with a FAILED result.
- **Malformed message:** rejected to DLQ.
- **Duplicate delivery:** skipped via `DuplicateJobGuard`.
- **Duration breach:** watchdog cancels the job.

## 10. Current limitations

- `DuplicateJobGuard` is in-memory; cross-worker dedup needs a durable store.
- `WorkerHealthIndicator` reflects only jobs known to the local guard.
- Process sandbox shares the host kernel (documented, not a hard boundary);
  Docker sandbox mode requires a docker socket (not enabled in compose).
- Cross-process cancellation only terminates local process trees.
- Pipeline engine (parser/validator) lives inside the worker; extraction to a
  shared `pipeline-engine/` module is planned for Phase 3 so the backend can
  validate YAML without executing it.
- No database, no artifact delivery, no cloud deployment — those belong to
  later phases.

## Done (definition of done)

Implemented worker module passes `mvn -B verify` locally (integration tests
with Docker), RabbitMQ topology is declared only in the worker, all
configuration is environment-driven (`worker/src/main/resources/application.yml`),
documentation in `docs/modules/worker.md` and `worker/README.md` is current,
and the module commits no generated files (`target/`, `*.class`, logs).
