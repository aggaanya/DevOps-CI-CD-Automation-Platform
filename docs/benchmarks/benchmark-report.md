# Benchmark Report

**Date:** 2026-09-28
**Environment:** Docker Compose (PostgreSQL 16 + RabbitMQ 3.13 + backend + worker)

## Executive Summary

The CI/CD platform's DAG-based parallel execution engine has been implemented,
verified, and benchmarked with real pipeline runs. The worker now receives
declared job steps from the control plane's PipelineVersion (not from the Git
repository), enabling controlled benchmarking.

## Test Results Summary

| Test Category | Tests | Passed | Failed | Skipped |
|---------------|-------|--------|--------|---------|
| Backend tests | 439 | 439 | 0 | 0 |
| Worker tests | 84 | 84 | 0 | 0 |

## Real E2E Pipeline Validation

| Component | Status | Evidence |
|-----------|--------|----------|
| PipelineVersion | PASS | Created via API |
| RabbitMQ pipeline-jobs | PASS | Worker consumed message |
| Worker | PASS | Executed declared steps (control-plane mode) |
| Git checkout | PASS | Commit 39956a86df5ce8955ab02bbc12d6f0036ea1b29b |
| Pipeline execution | PASS | Run 34c6a218 → SUCCESS |
| Final run status | SUCCESS | pipeline_runs.status = SUCCESS |

Evidence: e2e-control-plane-worker-validation.md

## Parallel Execution Proof

**Result:** VERIFIED

TEST and SCAN started at the same instant (12:27:40.440, 0.2ms apart) and
overlapped for ~10.5 seconds. PACKAGE started only after both finished.

Evidence: parallel-execution-validation.md

## Sequential vs Parallel Benchmark

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

## 50+ Pipeline Reliability

| Metric | Value |
|--------|-------|
| Runs completed | 53 |
| Successful | 53 |
| Failed | 0 |
| Success rate | 100% |

Evidence: reliability-test.md, reliability-test-50runs.csv

## Failure Propagation

| Job | Status |
|-----|--------|
| compile (BUILD) | SUCCESS |
| unit-test (TEST) | FAILED |
| security-scan (SCAN) | SKIPPED |
| package (PACKAGE) | SKIPPED |
| RUN | FAILED |

Evidence: failure-propagation.md

## Webhook Security

| Test | Result |
|------|--------|
| Valid signature | 202 Accepted |
| Invalid signature | 403 Forbidden |
| Duplicate delivery ID | Idempotent |

Evidence: webhook-verification.md

## Manual Deployment Step Analysis

**Baseline (9 documented steps):**
1. Build Docker images locally — **AUTOMATED**
2. Push images to ACR — **AUTOMATED**
3. Update manifests with image tags — **AUTOMATED**
4. Apply manifests to Azure — **AUTOMATED**
5. Verify deployment health — **NOT AUTOMATED**
6. Run smoke tests — **NOT AUTOMATED**
7. Update DNS/traffic routing — **NOT AUTOMATED**
8. Monitor for issues — **NOT AUTOMATED**
9. Rollback procedure — **NOT AUTOMATED**

**Result:** 4 of 9 steps automated. The 89% claim is NOT defensible.

## Deployment Time Measurement

| Environment | Status | Notes |
|-------------|--------|-------|
| Local Docker | Measurable | docker-compose up |
| Azure | NOT MEASURED | No active Azure subscription |
| Terraform | Static validation | fmt/validate pass |
| GitHub Actions | Static validation | Workflow syntax valid |

## Azure Validation Status

- Terraform fmt: PASS
- Terraform validate: PASS
- GitHub Actions YAML: PASS (OIDC, no secrets)
- Live Azure deployment: NOT AVAILABLE (no subscription)

## Evidence Files

- `e2e-control-plane-worker-validation.md`
- `parallel-execution-validation.md`
- `sequential-vs-parallel-benchmark.md`
- `reliability-test.md`
- `failure-propagation.md`
- `webhook-verification.md`
- `resume-validation-table.md`
- `FINAL_VALIDATION_SUMMARY.md`
- `benchmark-summary.json`
