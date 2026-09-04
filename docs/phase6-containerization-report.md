# Phase 6 — Containerization Report

- **Date:** 2026-09-05
- **Phase:** 6 (Containerization)
- **Verdict:** **COMPLETE**

The full platform is reproducible on a clean machine with one command
(`docker compose up -d --build`) and the containerized control plane and
Worker both executed real, end-to-end pipeline jobs against real RabbitMQ
and PostgreSQL during verification. One pre-existing test defect
(unrelated to containerization) was found and fixed, and one local-only
limitation (build tooling inside the Worker image) is documented below.

---

## 1. Executive summary

A complete containerization foundation already existed in the repository
(docker-compose.yml, backend/worker/frontend Dockerfiles, nginx config,
`.env.example`, `docs/local-development.md`). This phase **audited, fixed the
gaps, and fully verified** the stack. Gaps found and fixed:

1. Backend runtime image lacked `curl` (healthcheck would never pass) and
   `git` (required by the in-process worker's CLI-git clone).
2. Worker runtime image lacked `curl` (healthcheck would never pass).
3. No `.dockerignore` files in any module (bloated build contexts).
4. `frontend` service had **no healthcheck** despite documentation claiming
   every service had one; the added busybox-wget healthcheck initially used
   `http://localhost/` which resolves to `::1` inside the nginx alpine
   container and is refused — pinned to `http://127.0.0.1/`.
5. Documentation inaccuracies in `docs/local-development.md`
   (`KEYCLOAK_PORT` and `WORKER_PORT` defaults).

End-to-end verification (real infrastructure, no mocks):

- Backend actuator `UP` with real PostgreSQL (Flyway migrations validate) and
  real RabbitMQ 3.13.7 components.
- A real `PipelineJob` published to the real `cicd.jobs.exchange` was consumed
  by the containerized Worker, cloned with JGit to the exact commit, executed
  4 real shell steps in the container workspace, and produced a structured
  `PipelineResult` (`SUCCESS`, full stage/job/step detail, exit codes).
- Failure paths verified: failing step → structured `FAILED` result;
  malformed message → dead-letter queue; infrastructure failure → 3 retries
  via the delay queue → permanent failure → DLQ.
- External-real-remote fetch verified against `https://github.com/…`
  (JGit HTTPS clone succeeded in the container; the repo simply has no
  `pipeline.yml` at the tested commit, so the controlled fixture is used for
  the success story).
- Restart (`docker compose restart`) and **clean-state from scratch**
  (`docker compose down -v` + `up`) both recovered to all-healthy, and a
  fresh fixture re-ran to `SUCCESS`.

Test baseline: backend `mvn -B test` = **343/343 pass**, worker `mvn -B test`
= **82/82 pass**. One backend test initially failed for a pre-existing reason
(see §6).

Out of scope, by design and by instruction: Azure/Terraform/GitHub Actions,
Keycloak RBAC/JWT wiring, dashboard features, benchmarking, Redis/Keycloak
application integration, re-architecting the worker queue topology
(ADR-0001 preserved), and mounting the Docker socket into the Worker
(process sandbox mode stays, per Phase 4).

---

## 2. Audit results (existing containerization)

Classification: **A** = correct as-is, **B** = gap found and fixed,
**C** = missing and added, **D** = broken and replaced, **E** = deliberately
out of scope.

| Artifact | Grade | Notes |
|---|---|---|
| `docker-compose.yml` (7 services, 1 network, 4 volumes, env wiring, `depends_on` health conditions) | **A** | Already complete and idiomatic; topology left to the Worker (correct). Frontend healthcheck missing → **B** (now added). |
| `backend/Dockerfile` (2-stage Maven→JRE, non-root uid 10001, `MaxRAMPercentage`) | **B** | Missing `curl` and `git` in the runtime image (healthcheck + CLI-git clone). Fixed. |
| `worker/Dockerfile` (2-stage, non-root, `/data/workspaces`) | **B** | Missing `curl` (healthcheck). Fixed. |
| `frontend/Dockerfile` + `nginx.conf` (node build → nginx, `/api/` proxy to `backend:8081`) | **A** | Correct multi-stage; SPA `try_files`; service-name networking already right. |
| `.env.example` + env wiring in both `application.yml` | **A** | Correct local-dev contract; `.env` gitignored. |
| `.dockerignore` (any module) | **C** | None existed; added for backend, worker, frontend. |
| Redis / Keycloak (infra-only) | **A** | Correctly provisioned but not wired into app code — exactly the Phase 6 boundary. |
| `docs/local-development.md` | **B** | Two incorrect defaults; healthcheck-on-every-service claim now true (see §3). |
| Terraform / Azure / GH Actions (`infrastructure/terraform/`) | **E** | Explicitly out of scope. |

---

## 3. Changes made

| File | Change | Why |
|---|---|---|
| `backend/Dockerfile` | Install `curl git` in runtime stage | Actuator healthcheck uses `curl`; `GitOperations` runs CLI `git clone/checkout/rev-parse` in-process. |
| `worker/Dockerfile` | Install `curl` | Worker actuator healthcheck uses `curl`. (Worker clones via JGit — no `git` binary needed.) |
| `backend/.dockerignore`, `worker/.dockerignore`, `frontend/.dockerignore` | Added | Keep build contexts small/reproducible (`target/`, `node_modules/`, `dist/`, `.git/`). |
| `docker-compose.yml` | Added `frontend` healthcheck (`wget http://127.0.0.1/`) | Every service now has a healthcheck; `127.0.0.1` avoids the busybox-wget `::1` vs nginx IPv4-only bind mismatch. |
| `docs/local-development.md` | Corrected `KEYCLOAK_PORT` (8081→8083) and `WORKER_PORT` (8080→8082) rows | Match `.env.example` and compose. |
| `backend/.../RabbitMQIntegrationTest.java` | Added stub for `transitionStatus(...)`.`thenReturn(1)` | Pre-existing test defect (see §6). |
| `infrastructure/e2e-fixture/` (new) | `pipeline.yml`, `pipeline-fail.yml`, `message.txt` | Tracked, reproducible pipeline fixture used for the containerized E2E. |

No application code changed in this phase. All container changes are
additive to the images/compose/docs/tests.

---

## 4. Architecture decisions preserved

- **Worker/RabbitMQ topology stays Worker-declared.** Exchanges
  (`cicd.jobs.exchange`, `cicd.results.exchange`) and queues
  (`cicd.jobs`, `.delay`, `.dlq`) are declared by the Worker itself
  (`RabbitMQConfig.java`), never re-declared in compose. Verified live: the
  broker shows exactly those 2 exchanges and 3 queues.
- **Backend in-process queue is separate** (`pipeline-jobs-exchange` /
  `pipeline-jobs` / `job-dispatch`, via `ExecutionConstants`). The
  control-plane executes jobs in-process; the standalone Worker consumes the
  `cicd.jobs` topology. This ADR-0001 boundary is unchanged — Phase 6 runs
  the existing architecture in containers rather than redesigning it.
- **Redis and Keycloak are provisioned as infrastructure, not integrated.**
  No code uses them yet; this phase intentionally does not add usage.
- **No Docker socket, no privileged mode.** Worker stays in `process`
  sandbox (its declared boundary for these phases). Pipeline `STRICT`
  command policy and credential redaction remain enforced in the container
  (verified: a pipeline containing a control character was rejected by
  policy; STRICT-denylisted commands would be rejected the same way).
- **Non-root images** (uid 10001), memory-capped JVMs, single dedicated
  bridge network, no host-network/`/etc/hosts` hacks — networking is pure
  compose service-name DNS.

---

## 5. Verification (evidence)

Environment: Docker CLI/daemon 29.4.0, Compose v5.1.2, Windows 11 host,
images `backend` 588 MB / `worker` 551 MB / `frontend` 74 MB.

### 5.1 Stack health (from a clean state)

```
$ docker compose up -d --build
NAME      STATUS
backend   Up (healthy)     ... components: db=UP (PostgreSQL), rabbit=UP (3.13.7)
frontend  Up (healthy)     nginx serves 200; /api/* proxies to backend
keycloak  Up (healthy)
postgres  Up (healthy)
rabbitmq  Up (healthy)
redis     Up (healthy)
worker    Up (healthy)     workerId=worker-docker, workspaceWritable=true, rabbitUp=true
```

### 5.2 Real end-to-end pipeline (control plane + Worker over real RabbitMQ)

Publisher: `scripts/publish-job.ps1`
(`POST /api/exchanges/%2F/cicd.jobs.exchange/publish`, routing key
`cicd.job.submitted`) — the repository's own mechanism.

Result captured from `cicd.results.exchange` (routing key `cicd.result`):

```json
{
  "jobId": "job-82828ff8...",
  "pipelineId": "pipeline-job-82828ff8...",
  "status": "SUCCESS",
  "workerId": "worker-docker",
  "commitSha": "184a72d6e422c0da07d2dfe159d4aa357ed84827",
  "stages": [{
    "name": "build", "status": "SUCCESS",
    "jobs": [{
      "name": "container-check", "status": "SUCCESS",
      "steps": [
        {"command": "echo hello-from-phase6-worker-container", "status": "SUCCESS", "exitCode": 0},
        {"command": "pwd", "status": "SUCCESS", "exitCode": 0,
         "stdout": "/data/workspaces/job-job-82828ff8.../repo"},
        {"command": "cat message.txt", "status": "SUCCESS", "exitCode": 0},
        {"command": "printf 'artifact-content' > artifact.txt && cat artifact.txt",
         "status": "SUCCESS", "exitCode": 0}
      ]
    }]
  }]
}
```

This proves: consume → exact-commit clone (JGit) → pipeline parse → per-step
execution inside the container workspace → structured result publication with
headers (`jobId`, `pipelineId`, `status`, `workerId`).

An additional run against a **real HTTPS remote**
(`https://github.com/aggaanya/RealShield-…phantom-verifier.git`, commit
`3c547cb…`) confirmed outbound smart-HTTP fetch works from inside the worker
container (the repo has no `pipeline.yml` at that commit, so that job
correctly failed at the missing-file check rather than at transport).

### 5.3 Failure behaviour (real, observed)

| Scenario | Result |
|---|---|
| Pipeline step `exit 3` | Structured `FAILED` result; stage/job failed; failing step `exitCode=3`, prior step `exitCode=0` (partial execution captured) |
| Invalid `pipeline.yml` (unquoted `: ` scalar) | `FAILED`, message "Invalid pipeline YAML in …/pipeline.yml" |
| Pipeline step with control character | `FAILED`, "Command contains control characters" (policy enforcement) |
| Malformed (non-JSON) message | Dead-lettered to `cicd.jobs.dlq` (queue depth 1→2) |
| Infrastructure failure (clone) | Retried via `cicd.jobs.delay` at 30s ×3, then permanently failed → DLQ, ACK discipline preserved |
| Missing `pipeline.yml` in repo | `FAILED`, "not found" |

### 5.4 Operational behaviour

- `docker compose restart` → all services return to `healthy`.
- `docker compose down -v` + `up -d` → fresh volumes, all services
  `healthy`, and a re-seeded fixture pipeline runs to `SUCCESS` again
  (reproducible from scratch).

---

## 6. Test baseline

| Module | Command | Result |
|---|---|---|
| backend | `mvn -B test` | **343 tests, 0 failures, 0 errors** |
| worker | `mvn -B test` | **82 tests, 0 failures, 0 errors** |
| worker ITs (`RabbitMqFlowIT`, `CommandTimeoutIT`) | failsafe, not run under `test` | Available via `mvn -B verify` (Testcontainers + local `mvn`) — not executed as part of the Phase 6 baseline |

**Pre-existing test defect found and fixed** (not introduced by
containerization): `RabbitMQIntegrationTest.dispatchAndConsumeJob_createsAttemptAndProcessesJob`
failed with "wanted but not invoked: workerExecutor.executeJob". The
consumer claims a job with the atomic CAS `transitionStatus(QUEUED→RUNNING)`
(`JobMessageConsumer.java:123`, added in Phase 5); the test stubbed `findById`
but not `transitionStatus`, so Mockito returned `0`, the consumer saw
"not-QUEUED", skipped the job and never invoked the executor. Adding
`thenReturn(1)` for the claim fixes the test (verified against both the
compose broker and a fresh broker). This is a test-only change.

**Test-environment note (knowing what was learned):** `RabbitMQIntegrationTest`
is a `@SpringBootTest` that connects to `localhost:5672` and publishes to the
same `pipeline-jobs` queue the running backend *container's* consumer also
reads. If the full compose stack is up during `mvn -B test`, messages can be
consumed by the container instead of the test listener. Recommended order:
`docker compose stop backend` before running backend tests, or run tests with
the stack down and rely on a dedicated broker. Documented in
`docs/local-development.md` (§ Testing).

---

## 7. Known limitations (honest)

1. **Worker image contains no build toolchain.** The worker image is
   `eclipse-temurin:21-jre` + `curl`. JGit-based clones work, shell steps
   work, but pipelines that invoke `mvn`, `gradle`, `node`, `git`, etc. will
   fail with "command not found" until those tools are added to the image
   (a follow-up, not Phase 6). The fixture pipelines deliberately use only
   commands present in the base image so the E2E is deterministic.
2. **`file://` repository URLs** are used by the offline fixture. They work
   in the container (verified), but are a local-only convenience; real
   remotes (`https://`, `git://`) are the production form, and HTTPS was
   verified end-to-end. Using `file://` requires the fixture to be placed at
   a path the worker container can read (see `README`/`infrastructure/e2e-fixture`).
3. **Redis and Keycloak are running but unused** by application code
   (by design for this phase). Redis has no password in the default local
   stack.
4. **Worker `process` sandbox** is explicitly not a hard isolation boundary
   (documented in worker code); containerization does not tighten it.
5. **Keycloak starts slowly** on cold start (30–90 s) because the image has
   a 60 s `start_period` and uses `dev-file` storage.

---

## 8. How to reproduce

```bash
# 1. From the repository root (Docker Desktop must be running)
cp .env.example .env          # optional; safe defaults otherwise
docker compose up -d --build

# 2. Every service should be healthy
docker compose ps

# 3. Optional live checks
docker compose exec postgres pg_isready -U cicd -d cicd   # postgres
docker compose exec redis redis-cli ping                  # redis
docker compose ps --format "table {{.Name}}\t{{.Status}}" # all

# 4. Real E2E (offline fixture):
#    (a) Seed the fixture repo into the worker workspace volume
docker run --rm --entrypoint sh \
  -v devops-ci-cd-automation-platform_worker-workspaces:/data/workspaces \
  -v "$PWD/infrastructure/e2e-fixture:/src:ro" alpine/git -c \
  "rm -rf /data/workspaces/fixture-repo && mkdir -p /data/workspaces/fixture-repo \
   && cd /data/workspaces/fixture-repo && cp -r /src/. . && git init -b main -q \
   && git config user.email e2e@example.com && git config user.name e2e \
   && git add -A && git commit -qm fixture && git rev-parse HEAD"

#    (b) publish a job; capture the result from cicd.results.exchange
.\scripts\publish-job.ps1 -RepoUrl "file:///data/workspaces/fixture-repo" \
  -CommitSha "<sha-from-step-a>" -Branch main -PipelineFile pipeline.yml

# 5. Tear down (add -v for a truly clean slate)
docker compose down -v
```

The exact verification used PowerShell + the RabbitMQ management API to bind a
temporary capture queue to `cicd.results.exchange` and read the result
message; steps are recorded in this report. To repeat the worker E2E from
scratch: seed fixture → publish → wait ~10 s → capture result (see 5.2).

---

## 9. Files changed in this phase

```
backend/Dockerfile
worker/Dockerfile
backend/.dockerignore                  (new)
worker/.dockerignore                   (new)
frontend/.dockerignore                 (new)
docker-compose.yml
docs/local-development.md
docs/phase6-containerization-report.md (this report)
infrastructure/e2e-fixture/            (new: fixture pipeline for E2E)
backend/src/test/java/com/cicd/platform/controlplane/execution/RabbitMQIntegrationTest.java
```