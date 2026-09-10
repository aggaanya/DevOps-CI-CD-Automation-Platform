# DevOps CI/CD Automation Platform — Recruiter Runbook

This repository is a production-minded, educational CI/CD orchestration platform. It separates a **control plane** (a Spring Boot API that decides what should happen) from a **data plane** (a separate worker service that executes jobs), and it coordinates everything through RabbitMQ with PostgreSQL as the source of truth. You can take the whole platform from "clean clone" to a running, logged-in dashboard in a few commands with Docker Compose — no local JDK, Maven, or Node.js required.

## Architecture in Simple Terms

```
GitHub Repository (clone/edit/push)
        │
        ▼
CI/CD Platform – Control Plane (Spring Boot REST API, port 8081)
        │                     │
        │  triggers a run / enqueues a job
        ▼                     ▼
   PostgreSQL ◄────────── RabbitMQ ──────────► Docker Worker (port 8082)
   (Flyway-managed,    (jobs queue, delay    (clones repo, runs pipeline.yml)
    port 5432)          queue, DLQ, results)
                                  │
                                  ▼
                         Pipeline Execution
                                  │  publishes PipelineResult
                                  ▼
                         Result Persistence (PostgreSQL)
                                  │
                                  ▼
                         React Dashboard (port 3000, Nginx)
```

Authentication sits beside the flow: **Keycloak** (port 8083) is the OIDC identity provider. The React app signs in through it, and the backend validates the JWT + roles on every API call (except health checks and webhooks).

### Major Technologies (all verified in the repository)

- **Backend — control plane**: Java 21, Spring Boot 3.5.16, Spring Web, Spring Data JPA, Spring AMQP, Spring Security / OAuth2 Resource Server (JWT), Flyway, Springdoc OpenAPI/Swagger, HikariCP, SnakeYAML
- **Worker — data plane**: Java 21, Spring Boot, Spring AMQP, JGit (git clone/checkout), Prometheus Micrometer, Testcontainers
- **Frontend**: React 18 + TypeScript, Vite 6, oidc-client-ts (OIDC/PKCE), Nginx (served via Docker)
- **Infrastructure**: PostgreSQL 16, RabbitMQ 3.13, Redis 7, Keycloak 25.0, Docker Compose, Terraform (Azure), GitHub Actions
- **Config/code quality**: `.gitleaks.toml` secret scanning, `osv-scanner` dependency scanning

---

# 1. What This Project Demonstrates

Implemented capabilities (confirmed by inspecting the code, not inferred):

- **Pipeline creation & orchestration** — submit YAML pipeline definitions, validated in 3 phases (schema → semantic → DFS dependency-cycle detection), stored as immutable, auto-incremented versions
- **DAG scheduling** — `dependsOn` at both stage and job level; stages without explicit `dependsOn` run strictly in order
- **RabbitMQ-based asynchronous job dispatch** — control-plane topology (`pipeline-jobs-*`, outbox) and a separate worker topology (`cicd.jobs.exchange` → `cicd.jobs` / `cicd.jobs.delay` / `cicd.jobs.dlq`), manual ACK throughout
- **Isolated worker execution** — the worker clones a repo via JGit, checks out an exact commit SHA, parses and validates the pipeline YAML, and runs its steps in a sandboxed workspace (process sandbox in the local/Docker setup)
- **Retry & dead-letter behavior** — worker-level retries via a TTL delay queue with an `x-retry-count` header, exhausted retries reject to the DLQ; `DuplicateJobGuard` dedupes at-least-once delivery
- **Transactional outbox pattern** — lifecycle events (`RUN_STARTED`, `JOB_COMPLETED`, `RUN_COMPLETED`,… ) are written in the same DB transaction and polled out to RabbitMQ every 5s
- **Webhook ingestion** — GitHub webhooks with **HMAC-SHA256 signature verification** and GitLab secret-token verification; idempotent ingestion keyed on provider + delivery ID
- **Keycloak authentication (OIDC)** — PKCE authorization-code flow, JWKS-validated JWT `iss`/signature on the backend, silent renewal
- **Role-based access control (RBAC)** — realm roles `ADMIN` / `DEVELOPER` / `VIEWER` from the JWT feed method security; per-project memberships gate read/write/admin actions
- **PostgreSQL persistence** — versioned Flyway migrations (V1–V4) create the full domain schema (organizations, projects, repositories, pipelines, versions, runs, stages, jobs, attempts, users, memberships, worker results, webhook events, outbox events, artifacts, deployments, audit)
- **React dashboard** — Overview, Pipelines, Runs, Workers, Repositories, Artifacts, Infrastructure (live service health), Settings
- **Execution-result tracking** — results published by the worker are persisted and surfaced in the dashboard (`/api/v1/executions`)
- **Docker-based local deployment** — a single `docker compose` file brings up the entire stack with healthchecks
- **Terraform / Azure deployment infrastructure** — creates a resource group, VNet, Container Apps Environment, ACR, PostgreSQL flexible server, Key Vault, Azure Files shares, and 4 container apps (`infrastructure/terraform/`)
- **GitHub Actions CI/CD** — `ci.yml` (Java tests × backend/worker, frontend build, gitleaks + osv-scanner, terraform validate/plan) and `deploy.yml` (Azure OIDC → Terraform apply → immutable image build/push → Container Apps rollout)

**Explicitly not part of the local demo** (present but require external services/credentials):

- **Azure deployment / Terraform apply** — needs an Azure subscription + OIDC secrets. **Not required for the local demo.**
- **Redis** — provisioned by Compose but not yet used as an application cache in this phase.
- **Webhook end-to-end** — the GitHub/GitLab webhook *secrets* are empty by default; signature verification would reject unconfigured deliveries, so webhook tests are optional only after you set `WEBHOOK_GITHUB_SECRET`/`WEBHOOK_GITLAB_SECRET`.

---

# 2. Prerequisites

Everything in this runbook runs inside Docker, so **no JDK, Maven, Node.js, or npm is needed on your host**. You need:

| Tool | Why | Minimum |
|---|---|---|
| **Docker Desktop** | Runs all containers (Compose file uses healthchecks, profiles, and service conditions) | Docker Engine 24+/recursive Docker Desktop 4.x |
| **Docker Compose v2** | Ships with Docker Desktop; all commands use `docker compose` clauses in `docker-compose.yml` | Compose v2 |
| **Git** | Cloning the repository (and the optional worker execution demo clones a repo) | Git 2.x |
| A browser | Dashboard at `http://localhost:3000` | any modern browser |

> Optional, only if you want to run unit tests or run a module directly on the host: **JDK 21** and **Maven 3.9+** (backend/worker), **Node 20** (frontend). None are needed for the hands-on demo.

Free host ports (all configurable through `.env`): `3000` (frontend), `8081` (backend), `8082` (worker), `8083` (Keycloak), `5672`/`15672` (RabbitMQ), `5432` (PostgreSQL), `6379` (Redis).

Verify the tools in **PowerShell**:

```powershell
git --version
docker --version
docker compose version
```

If `docker compose version` prints a version, you're ready. (If the Docker engine is not running, start Docker Desktop first and wait for the whale icon to show "running".)

---

# 3. Run the Whole Platform Locally

All commands are run from the repository root in PowerShell.

### Step 3.1 — Clone the repository

```powershell
git clone https://github.com/aggaanya/DevOps-CI-CD-Automation-Platform.git
cd DevOps-CI-CD-Automation-Platform
```

### Step 3.2 — (Optional) Create a local `.env`

The stack runs unchanged on its built-in defaults, so this step is optional. Creating the file lets you override ports/credentials:

```powershell
Copy-Item .env.example .env   # optional; safe default values exist without it
```

`.env` is gitignored — never commit it.

### Step 3.3 — Build and start the full stack

```powershell
docker compose --build up -d --profile provision
```

This builds the backend, worker, and frontend images (a few minutes on first run) and starts: RabbitMQ, PostgreSQL, Redis, Keycloak (with the `cicd-platform` realm imported), the provisioning one-shot container, the backend, and the frontend.

If the demo users were not created on a previous run, provision them explicitly (idempotent):

```powershell
docker compose --profile provision run --rm provision
```

### Step 3.4 — Wait until everything is healthy

```powershell
docker compose ps
```

Wait until every service shows `healthy` / `running`. Keycloak's first cold start can take **30–90 seconds**; the backend and frontend wait for their dependencies before becoming healthy.

Expected services and their host ports:

| Service | Host URL / Port |
|---|---|
| Frontend (React dashboard) | `http://localhost:3000` |
| Backend API (control plane) | `http://localhost:8081` |
| Worker (actuator) | `http://localhost:8082/actuator/health` |
| Keycloak (admin console) | `http://localhost:8083` |
| RabbitMQ management UI | `http://localhost:15672` (guest / guest) |
| PostgreSQL | `localhost:5432` (cicd / cicd / db cicd) |

---

# 4. Explore the Running Platform

### 4.1 Walk through the dashboard (login is automatic)

1. Open `http://localhost:3000`.
2. If you're not signed in, you'll be redirected to **Keycloak** (`http://localhost:8083/realms/cicd-platform`). Sign in with any demo account:

   | Role | Username | Password |
   |---|---|---|
   | ADMIN | `admin@example.local` | `admin-password` |
   | DEVELOPER | `developer@example.local` | `developer-password` |
   | VIEWER | `viewer@example.local` | `viewer-password` |

3. You land on the dashboard. The sidebar shows **Overview, Pipelines, Runs, Workers, Repositories, Artifacts, Infrastructure, Settings**. The **Infrastructure** page reloads service health from the backend every 15s.
4. Sign out and back in as a second user to see the role badges (Admin / Developer / Viewer) in the top-right — RBAC in action.

### 4.2 Verify the control plane directly

```powershell
Invoke-RestMethod http://localhost:8081/api/v1/health | ConvertTo-Json
Invoke-RestMethod http://localhost:8082/actuator/health | ConvertTo-Json
```

- Swagger UI: `http://localhost:8081/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8081/api-docs`
- RabbitMQ queues/DLX: `http://localhost:15672`

> **Auth note (important):** every `/api/v1` endpoint **except** `GET /api/v1/health` and `POST /api/v1/webhooks/**` requires a Bearer JWT from Keycloak. The dashboard attaches it automatically, so the simplest demo path is through the UI. If you script the REST API yourself, you must supply `Authorization: Bearer <token>` (grab one from your browser's DevTools → Network → any `/api/v1` request, or the Keycloak console).

---

# 5. Run an Example Pipeline

There are two real execution paths. The **dashboard path (1)** is the cleanest for a demo; the **API path (2)** shows the orchestration engine's validation + DAG scheduling.

### Path 1 — Worker-executed run from the dashboard (requires a real repo)

The dashboard's **Trigger Run** form posts to `POST /api/v1/executions/trigger`, which enqueues a job onto the worker's RabbitMQ topology. The worker then **clones a real repository, checks out the given commit SHA, reads `pipeline.yml`, and executes its steps**. So this path needs a repository the worker container can reach:

1. Add a project + organization and **Connect a Repository** (with a `pipeline.yml` at the commit you reference).
2. **Trigger Run** → pick the repository, enter a commit SHA + branch.
3. Watch it appear under **Runs** and **Workers** with live status refreshes.

Example worker-pipeline YAML format (from `infrastructure/e2e-fixture/pipeline.yml`):

```yaml
pipeline:
  name: phase6-fixture
  stages:
    - name: build
      jobs:
        - name: container-check
          steps:
            - run: "echo hello-from-phase6-worker-container"
            - run: "pwd"
            - run: "cat message.txt"
```

A scripted alternative that publishes a job straight onto the worker's queue via the RabbitMQ management API:

```powershell
.\scripts\publish-job.ps1 -RepoUrl "https://github.com/<owner>/<repo>.git" -CommitSha "<sha>" -PipelineFile "pipeline.yml"
```

### Path 2 — Orchestration-engine run over the REST API

This exercises the control plane: YAML validation, versioned pipelines, stage/job creation, and dependent-job dispatch. Create the resources, then use any READER of the response bodies for the returned UUIDs:

```powershell
$headers = @{ Authorization = "Bearer $TOKEN"; "Content-Type" = "application/json" }

# 1. Organization
$org = Invoke-RestMethod -Uri http://localhost:8081/api/v1/organizations -Method Post -Headers $headers `
  -Body '{"name":"My Org","slug":"my-org"}'

# 2. Project
$proj = Invoke-RestMethod -Uri http://localhost:8081/api/v1/projects -Method Post -Headers $headers `
  -Body ("{`"organizationId`":`"" + $org.id + "`",`"name`":`"Backend`",`"slug`":`"backend`"}")

# 3. Pipeline
$pipe = Invoke-RestMethod -Uri http://localhost:8081/api/v1/pipelines -Method Post -Headers $headers `
  -Body ("{`"projectId`":`"" + $proj.id + "`",`"name`":`"CI Pipeline`"}")

# 4. Submit pipeline YAML (validated + stored as version 1)
$yaml = @'
pipeline:
  name: CI Pipeline
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
'@
$version = Invoke-RestMethod -Uri ("http://localhost:8081/api/v1/pipelines/" + $pipe.id + "/versions") `
  -Method Post -Headers $headers -Body ("{`"yamlContent`":`"" + ($yaml -replace "`r?`n", "\n") + "`"}")

# 5. Trigger a run
$run = Invoke-RestMethod -Uri http://localhost:8081/api/v1/runs -Method Post -Headers $headers `
  -Body ("{`"pipelineVersionId`":`"" + $version.id + "`",`"commitSha`":`"abc123def456`",`"branch`":`"main`",`"triggeredBy`":`"demo`"}")

# 6. Inspect stages / jobs / attempts
Invoke-RestMethod -Uri ("http://localhost:8081/api/v1/runs/" + $run.id + "/stages") -Headers $headers
```

Internally: the YAML is parsed (SnakeYAML), run through schema → semantic → cycle-detection validators, persisted as an immutable version; on trigger the orchestrator creates stages/jobs, evaluates `dependsOn`, dispatches eligible jobs to RabbitMQ, consumes results, and propagates statuses job → stage → run.

---

# 6. Optional (not needed for the demo)

- **Unit/integration tests** — if you have `mvn` locally, from each module: `cd backend; mvn -B test` and `cd worker; mvn -B verify` (worker integration tests use Testcontainers, so Docker is still required).
- **GitHub Actions CI/CD** — `ci.yml` runs on every PR/push (Java tests, frontend build, gitleaks + osv-scanner, `terraform validate`/`plan`). Runs automatically on GitHub after you push — nothing to do locally.
- **Azure deployment** — `infrastructure/terraform/` + `deploy.yml` provision and deploy to Azure Container Apps via OIDC. Requires an Azure subscription and repository secrets (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`). **Not required for the local demo.**
- **Keycloak admin console** — `http://localhost:8083` (admin / admin) to inspect the `cicd-platform` realm, the `cicd-frontend` public client (PKCE, redirect `http://localhost:3000/*`), and roles.

---

# 7. Troubleshooting Cheat Sheet

| Symptom | Likely cause | Fix |
|---|---|---|
| Port already in use on `up` | Host service on 3000/8081/8082/8083/5672/15672/5432/6379 | Override `*_PORT` in `.env`, or stop the conflicting service |
| Keycloak still `starting` | Cold start 30–90s; backend waits for it | `docker compose ps` again; wait for `healthy` |
| No demo users when logging in | Provision container didn't run | `docker compose --profile provision run --rm provision` |
| 401 on raw API calls | JWT required on everything except health/webhooks | Login via the UI, or send `Authorization: Bearer <token>` |
| Stale data / missing resources | Named volumes persist across runs | `docker compose down -v` to wipe local data |
| Stop everything | — | `docker compose down` (add `-v` to also remove volumes) |

---

## Good talking points at a glance

- Modular monolith control plane + isolated worker "data plane"; explicit boundary documented in `docs/adr/0001-modular-monolith-and-worker-boundary.md`.
- Reliability engineering: manual ACK (at-least-once), transactional outbox, worker-side retry via TTL delay queue → DLQ, in-process deduplication, immutable pipeline versions.
- Security posture: JWT/OIDC + realm roles + per-project RBAC memberships, GitHub HMAC-SHA256 webhook verification, constant-time signature comparison, command security policy (STRICT) + secret-named env blocking, path-traversal and git-URL sanitization, gitleaks + osv-scanner in CI.
- IaC & CI/CD: Terraform (Azure Container Apps/ACR/Postgres/Key Vault) applied through GitHub Actions with OIDC and immutable commit-SHA image tags — the repo practices what the product automates.