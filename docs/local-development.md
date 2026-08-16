# Local Development Environment

The local platform runs entirely through Docker Compose and is reproducible
with one command. It provides the infrastructure services the platform needs
plus the Phase 4 execution Worker.

## Prerequisites

- **Docker** with **Docker Compose** (Compose v2, `docker compose`).
- A **JDK 21** and **Maven 3.9+** only if you want to build/run the worker
  outside Docker or run its tests (`mvn -B verify`).
- **PowerShell** for `scripts/publish-job.ps1` (optional, Windows).
- Ports 5672, 15672, 5432, 6379, 8081, 8080 free on the host (all
  configurable via `.env`).

## Services

| Service | Image | Purpose | Exposed ports |
|---|---|---|---|
| `rabbitmq` | `rabbitmq:3.13-management-alpine` | Durable job queue / results | 5672 (AMQP), 15672 (management UI) |
| `postgres` | `postgres:16-alpine` | Durable platform state (later phases) | 5432 |
| `redis` | `redis:7-alpine` | Cache (reserved for later phases) | 6379 |
| `keycloak` | `quay.io/keycloak/keycloak:25.0` | OIDC identity (development mode) | 8081 |
| `worker` | built from `worker/` | Pipeline execution engine | 8080 (actuator) |

All services share one dedicated bridge network, `cicd-local`
(Docker network name `cicd-platform-local`).

The RabbitMQ topology (exchanges, queues, delay/DLQ) is **declared by the
Worker** in `RabbitMQConfig.java` and is intentionally not re-declared in
Docker Compose.

## Start

```bash
# from the repository root
cp .env.example .env        # optional; safe defaults exist without it
docker compose up --build   # foreground (add -d to run detached)
```

The Worker image is rebuilt from `worker/Dockerfile`; the other services use
their published images.

## Stop

```bash
docker compose down                 # stop and remove containers/network
docker compose down -v              # also remove named volumes (wipes local data)
```

## Configuration

All credentials and ports come from environment variables. Compose defaults
are safe local-development values; override them by creating `.env` from
`.env.example` (`.env` is gitignored).

| Variable | Default | Used by |
|---|---|---|
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `guest` / `guest` | `rabbitmq` broker and `worker` connection |
| `RABBITMQ_AMQP_PORT` / `RABBITMQ_MGMT_PORT` | `5672` / `15672` | host ports |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | `cicd` / `cicd` / `cicd` | `postgres` |
| `POSTGRES_PORT` | `5432` | host port |
| `REDIS_PORT` | `6379` | host port |
| `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` | `admin` / `admin` | Keycloak admin console |
| `KEYCLOAK_PORT` | `8081` | host port |
| `WORKER_PORT` | `8080` | host port |
| `WORKER_ID` | `worker-docker` | worker identity in results/logs |

Note: `RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD` drive both the broker's default
user and the worker's connection credentials, keeping the Worker →
RabbitMQ connection compatible with the current Worker implementation.

## Checking service health

Compose declares a healthcheck on every service.

```bash
docker compose ps                 # shows STATUS: healthy per service
docker compose ps --format "table {{.Name}}\t{{.Status}}\t{{.Health}}"
```

Per-service health status:

```bash
docker inspect --format '{{.Name}} {{.State.Health.Status}}' $(docker compose ps -q)
```

Direct endpoint checks:

| Service | Check |
|---|---|
| Worker | `Invoke-RestMethod http://localhost:8080/actuator/health` or `curl http://localhost:8080/actuator/health` |
| RabbitMQ | <http://localhost:15672> (management UI), or `docker compose exec rabbitmq rabbitmq-diagnostics -q ping` |
| PostgreSQL | `docker compose exec postgres pg_isready -U cicd -d cicd` |
| Redis | `docker compose exec redis redis-cli ping` (expects `PONG`) |
| Keycloak | <http://localhost:8081> (admin console), readiness: `http://localhost:9000/health/ready` inside the container |

Keycloak runs in development mode (`start-dev`) with an on-disk dev database
(`KC_DB=dev-file`) and health/metrics endpoints enabled. Its healthcheck uses a
`bash /dev/tcp` probe against the management port 9000 because the Keycloak
image has no `curl`/`wget`.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Port already in use on `up` | Host service on 5672/5432/6379/8081/8080 | Override the `*_PORT` variables in `.env`, or stop the conflicting service |
| Worker `starting`/`unhealthy` | RabbitMQ not healthy yet | `docker compose ps`; worker waits for `rabbitmq` (depends_on condition) |
| Keycloak `starting` | Cold start takes 30–90s | Wait; `docker compose ps` until healthy |
| `docker compose` errors | Missing/invalid `.env` | Remove or fix `.env`; defaults are safe |
| Stale data after config changes | Named volumes persist | `docker compose down -v` (wipes local data) |

## Limitations (local foundation only)

- PostgreSQL, Redis, and Keycloak are provisioned but **not yet wired into
  application code** — no entities, migrations, authentication logic, or
  integration. That is later phase work.
- Redis runs without a password (local-only; add `--requirepass` via
  `command` in `.env`-driven overrides if needed).
- Worker runs in `process` sandbox mode (no Docker socket mounted), as before.
