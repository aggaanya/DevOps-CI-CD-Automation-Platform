# ADR-0001: Modular Monolith and Worker Boundary

- **Status:** Accepted
- **Date:** 2026-08-16
- **Deciders:** Platform team
- **Related documents:** `PROJECT_SPECIFICATION.md` (§4.4, §10.2, §6.4)

## Context

The platform must orchestrate pipeline execution over a clearly defined
trust boundary: repository code and pipeline YAML are **untrusted** and must
never run inside the control-plane process or have direct access to the
database, secrets, or cloud control. The team is small (2–5 students over
12–16 weeks) and must ship a reliable MVP while keeping the architecture
capable of evolving toward an internal developer platform.

Four structural decisions follow from this context. This ADR records each of
them, the alternatives that were rejected, and the accepted trade-offs.

## Decision 1: A monorepo, not multiple repositories

The entire platform lives in a single git repository with clearly separated
top-level directories: `worker/`, `backend/` (planned), `frontend/` (planned),
`infrastructure/` (planned), `pipeline-engine/` (planned, currently inside
`worker/`), `docs/`, `scripts/`, `tests/` (planned), and `.github/workflows/`
(planned).

**Why**

- One atomic change can update worker, control-plane, and infrastructure code
  together; a DSL or message-contract change cannot drift between
  independently versioned repositories.
- A small team gets a single CI pipeline, single issue tracker, and single
  review flow.
- Local development (`docker-compose.yml`) can reference sibling modules by
  relative path without publishing internal packages.
- Consistent history: architecture decisions and their implementation land in
  one place, which matters for a final-year project evaluation.

**Alternatives considered**

- *Multi-repo (one per service):* rejected. Cross-service contract changes
  become coordinated multi-repo releases; CI/review overhead is high for a
  small team; no independent release cadence is actually needed at MVP scale.
- *Vendored/embedded worker:* rejected on security grounds (see Decision 2).

**Consequences**

- The repository can grow large; directory ownership and module docs
  (`docs/modules/`) are mandatory to keep boundaries legible.
- A full CI build compiles every module; later phases can add path-filtered
  workflows to limit blast radius.

## Decision 2: The Worker is a separate execution service

The execution engine runs as its own deployable Spring Boot service
(`worker/`), not as a library inside the control plane. It is the only
component that clones repositories, parses pipeline YAML, and executes
commands.

**Why**

- **Security/isolation:** the Worker executes untrusted repository code. A
  separate process, container, and (later) host/Container Apps Job means a
  compromised build cannot reach the control plane, the database, or secrets
  directly (least privilege per `PROJECT_SPECIFICATION.md` §6.4).
- **Independent scaling:** builds are bursty. The worker pool can scale
  horizontally without scaling the stateless API.
- **Independent failure modes:** a crashing or resource-starved worker must not
  take the API down; queued jobs survive worker loss and are retried.
- **No database credential for workers:** the worker publishes results through
  RabbitMQ and has no source-of-truth database access, reducing the attack
  surface.

**Alternatives considered**

- *In-process execution library:* rejected. This is the explicit trust
  violation the platform exists to avoid — repository shell commands would run
  inside the API process.
- *Shared binaries/scripts invoked by the API:* rejected. Not isolatable, not
  horizontally scalable, and hard to sandbox.

**Consequences**

- Two deployables and a message contract between them; the API and worker must
  agree on the `PipelineJob`/`PipelineResult` JSON shape.
- Cross-service tracing/log correlation must be carried explicitly (MDC
  context with `workerId`, `jobId`, `pipelineId`).
- Worker state (`DuplicateJobGuard`) is currently in-memory; cross-worker
  dedup requires a durable store in a later phase.

## Decision 3: The pipeline engine stays inside the Worker for now

The YAML parser/validator and execution model currently live in
`worker/src/main/java/.../pipeline/` and `execution/`. The spec allows a
shared `pipeline-engine/` module; for the MVP it remains a worker package.

**Why**

- The parser and the executor are only ever used together inside the Worker;
  there is no consumer in the control plane yet.
- Extracting a module now would add build/versioning overhead with no
  immediate benefit; the spec explicitly permits starting it as a worker/backend
  module (§10.2).
- The parser already enforces a *worker-side security boundary* (action
  allowlist, env validation) that is coupled to execution constraints.

**Alternatives considered**

- *Separate `pipeline-engine/` module now:* deferred. Once the backend needs
  schema validation for persisted pipelines (Phase 3) without executing them,
  the parser/validator should be extracted into a shared module so backend and
  worker validate identically.

**Consequences**

- Until extraction, the backend cannot validate YAML without the worker. This
  is acceptable for the MVP because the worker is the enforcing boundary; a
  Phase 3 change should extract the validation half into `pipeline-engine/`
  and keep only execution in the worker.

## Decision 4: RabbitMQ between job submission and execution

The control plane (or, today, `scripts/publish-job.ps1`) publishes a
`PipelineJob` to `cicd.jobs.exchange`; the Worker consumes from `cicd.jobs`,
executes, and publishes `PipelineResult` to `cicd.results.exchange`. The
topology (exchanges, queues, delay/DLQ) is declared by the Worker
(`RabbitMQConfig.java`).

**Why**

- **Durability/reliability:** a queued job survives API restart; manual
  acknowledgement means a message is only removed after a result is published
  or the message is routed to retry/DLQ (at-least-once).
- **Backpressure and burst handling:** workers pull at their own rate; build
  bursts queue rather than overload the API or a worker.
- **Retry/DLQ semantics for free:** transient infrastructure failures are
  delayed via a TTL queue (`x-retry-count` header); permanent failures land in
  `cicd.jobs.dlq` for operator visibility.
- **Clean control-plane/data-plane decoupling:** the control plane does not
  call workers directly; the queue is the integration contract.

**Alternatives considered**

- *HTTP/gRPC pull from a worker registry:* rejected — reimplements queueing,
  retries, and broker durability that RabbitMQ already provides.
- *Kafka:* rejected for the MVP — work-queue semantics, per-message ACK, and
  simpler operational burden favor RabbitMQ (§4.5). Kafka is the documented
  evolution path for high-volume event analytics.
- *Synchronous in-API execution:* rejected — violates Decision 2 and the
  availability requirement.

**Consequences**

- RabbitMQ becomes a critical infrastructure dependency; its health must be
  monitored and its state protected by a durable volume.
- At-least-once delivery requires idempotency — currently `DuplicateJobGuard`
  (in-memory) per process.
- The topology must be declared in exactly one place (the Worker) to avoid
  drift.

## Summary of trade-offs

| Decision | Accepted cost | Payoff |
|---|---|---|
| Monorepo | Single repo grows large; needs module discipline | Atomic cross-module changes, simple team flow |
| Separate Worker | Two deployables + message contract | Security isolation, independent scaling/failure |
| Engine inside Worker (now) | Backend can't validate YAML without worker yet | No premature module boundary; extraction planned for Phase 3 |
| RabbitMQ between planes | Operational dependency; at-least-once dedup needed | Durable, retryable, decoupled job dispatch |
