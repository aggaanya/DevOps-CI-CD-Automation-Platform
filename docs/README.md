# Documentation

This directory holds the project's technical documentation. It is organized by
purpose so that architecture decisions, module contracts, and operational
runbooks can be found independently of the source tree.

## Structure

```text
docs/
  README.md                         # this index
  adr/                              # Architecture Decision Records
    0001-modular-monolith-and-worker-boundary.md
  modules/                          # per-module definition-of-done documentation
    worker.md
```

## Sections

### `adr/` — Architecture Decision Records

Each file captures one significant architectural decision: the context, the
decision, alternatives considered, and consequences. Read these first when a
change touches the platform's boundaries or structure.

| ADR | Decision |
|---|---|
| `0001-modular-monolith-and-worker-boundary.md` | Monorepo layout, Worker as a separate execution service, pipeline engine inside the Worker, RabbitMQ between job submission and execution |

### `modules/` — Module documentation

One file per implementation module, following the module definition-of-done
template in `PROJECT_SPECIFICATION.md`:

1. Purpose and responsibility
2. Architecture/dependencies
3. Data model
4. API/event contract
5. Implementation
6. Security
7. Testing
8. Monitoring
9. Failure handling
10. Done

| Module | File | Status |
|---|---|---|
| Worker (execution engine) | `modules/worker.md` | Implemented (Phase 4) |

### Planned additions

- `runbooks/` — operator troubleshooting for worker, RabbitMQ, and (later)
  PostgreSQL, Azure, and the control plane.
- `adr/` — new ADRs as later phases (backend, webhook, artifact delivery)
  introduce decisions.
- `modules/` — one file per module when `backend/` and other components are
  implemented.

### Reports

| Phase | Topic | File |
|---|---|---|
| 5 | Containerization verification (pre-phase-6 audit) | `phase5-verification-report.md` |
| 5 | Security gap matrix | `phase5-security-gap-matrix.md` |
| 6 | Containerization | `phase6-containerization-report.md` |
| 8 | Terraform IaC + GitHub Actions CI/CD | `phase8-terraform-and-cicd.md` |

## Conventions

- Documentation is written in plain GitHub-flavored Markdown.
- ADRs follow the classic record: *Status, Context, Decision, Alternatives,
  Consequences*.
- Module docs must be kept in sync with the module they describe; update them
  in the same change that modifies the module.
- Never document secret values, credentials, or connection strings.
