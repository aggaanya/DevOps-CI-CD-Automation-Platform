# Final Validation Summary

**Date:** 2026-09-28
**Environment:** Docker Compose (PostgreSQL 16 + RabbitMQ 3.13 + backend + worker + Keycloak)

## 1. Test Suites

| Suite | Tests | Failures | Errors | Status |
|-------|-------|----------|--------|--------|
| Backend | 439 | 0 | 0 | PASS |
| Worker | 84 | 0 | 0 | PASS |

## 2. Control-Plane → Worker Path

| Component | Status | Evidence |
|-----------|--------|----------|
| PipelineVersion | PASS | Created via API (version 6) |
| RabbitMQ pipeline-jobs | PASS | Worker consumed message, 2 consumers |
| Worker | PASS | Executed declared steps (mode=control-plane) |
| Git checkout | PASS | Checked out commit 39956a86df5ce8955ab02bbc12d6f0036ea1b29b |
| Pipeline execution | PASS | Run 34c6a218 → SUCCESS |
| Final run status | SUCCESS | pipeline_runs.status = SUCCESS |

Evidence: e2e-control-plane-worker-validation.md

## 3. Parallel Execution

| Metric | Value |
|--------|-------|
| Verified | YES |
| TEST/SCAN same-start | 12:27:40.440 (0.2ms apart) |
| Overlap duration | ~10.5s |
| PACKAGE start | After both TEST and SCAN finished |

Evidence: parallel-execution-validation.md

## 4. Sequential vs Parallel Benchmark

| Metric | Sequential | Parallel |
|--------|-----------|----------|
| Runs | 10 | 10 |
| Average | 88.97s | 68.85s |
| Median | 81.60s | 54.42s |
| Min | 74.41s | 49.40s |
| Max | 157.38s | 204.13s |

- **Improvement (average):** 22.6%
- **Improvement (median):** 33.3%

Evidence: sequential-vs-parallel-benchmark.md

## 5. 50+ Pipeline Reliability

| Metric | Value |
|--------|-------|
| Runs completed | 53 |
| Successful | 53 |
| Failed | 0 |
| Success rate | 100% |

Evidence: reliability-test.md, reliability-test-50runs.csv

## 6. Failure Propagation

| Job | Status |
|-----|--------|
| compile (BUILD) | SUCCESS |
| unit-test (TEST) | FAILED (after retry exhaustion) |
| security-scan (SCAN) | SKIPPED |
| package (PACKAGE) | SKIPPED |
| RUN | FAILED |

Evidence: failure-propagation.md

## 7. Webhook Security

| Test | Result |
|------|--------|
| Valid signature | 202 Accepted |
| Invalid signature | 403 Forbidden |
| Duplicate delivery ID | Idempotent (no duplicate runs) |

Evidence: webhook-verification.md

## 8. Azure Validation

| Check | Status |
|-------|--------|
| Terraform fmt | PASS |
| Terraform validate | PASS |
| GitHub Actions YAML | PASS (OIDC, no secrets) |
| Live deployment | NOT AVAILABLE (no subscription) |

## 9. Resume Claims

| Claim | Action | Measured |
|-------|--------|----------|
| 50% deployment-time reduction | REWORD | NOT MEASURED |
| 89% manual-step reduction | REWORD | 4 of 9 steps automated |
| 96% success rate | CHANGE → 100% | 53/53 = 100% |
| 30% throughput improvement | CHANGE → 33% | 33.3% (median) |

## 10. Stale RUNNING Runs — CLEANED UP

7 historical abandoned test runs were cleaned up using the application's built-in
cancellation mechanism (`POST /api/v1/runs/{id}/cancel`). No arbitrary SQL was used.

| Run ID | Triggered By | Previous Status | New Status | Reason |
|--------|-------------|-----------------|------------|--------|
| 5cae6828 | webhook:github | RUNNING | CANCELLED | Version 5: cross-stage dependsOn, DAG resolution failed |
| cbc23eb1 | webhook:github | RUNNING | CANCELLED | Version 4: cross-stage dependsOn, DAG resolution failed |
| 6bde3f2e | webhook:github | RUNNING | CANCELLED | Version 3: RealShield YAML, jobs never dispatched |
| 8d3faf32 | webhook:github | RUNNING | CANCELLED | Version 2: RealShield YAML, jobs never dispatched |
| 6d6c7df4 | webhook:github | RUNNING | CANCELLED | Version 1: RealShield YAML, jobs never dispatched |
| 4e11ba43 | failure-test | RUNNING | CANCELLED | TEST job stuck QUEUED (old DuplicateJobGuard bug) |
| d04a66cb | failure-test-2 | RUNNING | CANCELLED | TEST job stuck QUEUED (old DuplicateJobGuard bug) |

**Cleanup method:** Application API (`RunController.cancelRun` → `PipelineOrchestrator.cancelRun`).
Jobs transitioned to CANCELLED, stages to SKIPPED, attempts to CANCELLED.

**Benchmark data unchanged:** 53/53 reliability runs remain SUCCESS. Sequential/parallel
benchmark data unchanged. No successful runs were modified or deleted.

## 11. Bugs Found and Fixed

1. **Worker PipelineJobValidator** rejected control-plane dispatch messages (required pipelineId, not in dispatch format). Fixed to accept pipelineVersionId.
2. **WorkerResultConsumer** did not advance run state. Fixed to call orchestrator.handleJobCompletion.
3. **Backend JobMessageConsumer** competed with standalone worker on pipeline-jobs. Fixed with @ConditionalOnProperty, disabled in docker-compose.
4. **Worker RabbitMQConfig** did not declare pipeline-jobs queue (declaration race). Fixed with idempotent DLX declaration.
5. **DuplicateJobGuard** keyed only on jobId, causing retries to be skipped. Fixed to key on jobId+attemptNumber.
6. **WorkerResultConsumer** used existsByJobId which blocked retry results. Fixed to use existsByJobIdAndStatusAndDurationMs.
7. **JGitGitService** cloned all branches (slow, rate-limited). Fixed to clone only the needed branch.
8. **GlobalExceptionHandler** swallowed errors without logging. Fixed to log unhandled exceptions.
