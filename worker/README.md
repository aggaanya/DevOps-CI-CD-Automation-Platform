# cicd-worker — Phase 4 execution engine

The worker consumes `PipelineJob` messages from RabbitMQ, clones the target
repository, checks out the **exact commit** referenced in the job, parses the
pipeline YAML, and executes its steps inside a sandboxed workspace. The
result is published as a structured `PipelineResult` back to RabbitMQ.

## Components

- `pom.xml` — Spring Boot 3.3.5, Java 21, JGit, Testcontainers, failsafe (`*IT`).
- `src/main/java/com/cicd/platform/worker/`
  - `messaging/PipelineJobConsumer` — manual-ACK consumer: result published
    before ACK; malformed jobs rejected to DLQ; transient failures retried
    through the TTL delay queue (`x-retry-count` header, max `worker.max-retries`).
  - `messaging/PipelineResultPublisher` — publishes `PipelineResult` to
    `cicd.results.exchange`, schedules retries via the delay queue.
  - `service/DuplicateJobGuard` — atomic per-`jobId` in-process dedup
    (RabbitMQ is at-least-once).
  - `git/JGitGitService` — clones (all branches, no checkout), fetches,
    verifies the requested SHA exists, detached-checkouts it, verifies HEAD.
  - `pipeline/` — loader, YAML parser and validator (names, workdir,
    env names/values, step limits, security).
  - `sandbox/` — `ExecutionSandbox` abstraction; `process` (default, local
    dev) and `docker` implementations; whitelisted environment only.
  - `security/CommandSecurityPolicy` — STRICT/RELAXED blocking of
    destructive/exfil commands and secret-named environment variables.
  - `execution/` — step/job/stage/pipeline executors, artifact collector,
    watchdog, per-job workspace.
  - `observability/` — Prometheus metrics + health indicators.

## RabbitMQ topology

- Exchanges: `cicd.jobs.exchange` (direct), `cicd.results.exchange` (direct).
- Queues: `cicd.jobs`, `cicd.jobs.delay` (TTL=retry-delay, DLX→jobs exchange),
  `cicd.jobs.dlq`.
- Routing keys: `cicd.job.submitted`, `cicd.job.delay`, `cicd.job.dead`,
  `cicd.result`.

## Local run (Docker)

```bash
docker compose up -d --build     # rabbitmq + worker
```

- Worker health: <http://localhost:8080/actuator/health>
- Prometheus metrics: <http://localhost:8080/actuator/prometheus>
- RabbitMQ UI: <http://localhost:15672> (guest/guest)

Submit a job:

```powershell
.\scripts\publish-job.ps1 -RepoUrl <url> -CommitSha <sha> [-Branch main] [-PipelineFile pipeline.yml]
```

## Local run (plain JVM, process sandbox)

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12"
cd worker
mvn -B verify                                   # unit + integration tests (needs Docker)
mvn -B package -DskipTests
java -jar target\cicd-worker-0.1.0.jar          # with RabbitMQ on localhost:5672
```

## Pipeline YAML contract

```yaml
pipeline:
  name: <name>
  stages:
    - name: <stage>
      jobs:
        - name: <job>
          workingDirectory: <subdir of repo>   # optional
          image: <docker image>                # optional; docker sandbox only
          env: { KEY: value }                  # optional
          artifacts: [ path, ... ]             # optional
          steps:
            - run: <shell command>             # runs via sh -c / cmd /c
            - run: <another command>
```

Semantics:
- Steps run in order; the first non-zero exit stops the job (no continue-on-error).
- `run` executes through the sandbox. `buildImage` runs `docker build` and is
  disabled by default (`WORKER_BUILD_IMAGE_ENABLED=false`).
- Every command is validated by `CommandSecurityPolicy` and every job env var
  by `PipelineValidator` (secret-named variables are blocked).

## Configuration (all environment-driven)

`worker/src/main/resources/application.yml` — key settings:

| Variable | Default | Purpose |
|---|---|---|
| `RABBITMQ_HOST/PORT/USERNAME/PASSWORD` | localhost/5672/guest/guest | broker |
| `WORKER_ID` | worker-local | worker identity in results/logs |
| `WORKSPACE_ROOT` | tmpdir/cicd-workspaces | per-job workspace root |
| `WORKER_COMMAND_TIMEOUT_MS` | 900000 | per-command timeout |
| `WORKER_MAX_PIPELINE_DURATION_MS` | 1800000 | whole-pipeline watchdog |
| `WORKER_MAX_CONCURRENCY` | 2 | parallel consumer threads |
| `WORKER_RETRY_ENABLED / MAX_RETRIES / RETRY_DELAY_MS` | true / 3 / 30000 | infra-failure retries |
| `WORKER_COMMAND_POLICY` | STRICT | command security policy |
| `WORKER_SANDBOX` | process | process or docker |
| `WORKER_BUILD_IMAGE_ENABLED` | false | allow buildImage steps |
| `CICD_*` | cicd.* | exchange/queue/routing-key names |
| `GIT_USERNAME / GIT_PASSWORD / GIT_TOKEN` | empty | optional authenticated clones |

## Security model

- Untrusted commands run in a child process / container, never in the JVM.
- Process sandbox passes a **whitelisted** environment (safe base set + job env).
- Docker sandbox uses `--cap-drop ALL --security-opt no-new-privileges --rm`,
  no docker socket, arg-list invocation (no host shell).
- Pipeline YAML and commands are validated before execution; workingDirectory
  may not escape the repo root.
- Secrets: command policy and env validator block credential-named variables;
  log collector captures only command output; the worker never logs repository
  credentials.

## Known limitations (Phase 5)

- `DuplicateJobGuard` is in-memory; cross-worker dedup needs a durable store.
- `WorkerHealthIndicator` reports running jobs from the guard only.
- Process sandbox shares the host kernel (documented, not a hard boundary).
- Cross-process cancellation only terminates local process trees.
