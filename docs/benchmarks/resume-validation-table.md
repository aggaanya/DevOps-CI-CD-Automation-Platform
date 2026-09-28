# Resume Validation Table

**Date:** 2026-09-28
**Method:** All claims evaluated against actual measured evidence from the
Docker Compose environment (PostgreSQL + RabbitMQ + backend + worker).

| Resume Claim | Baseline | Platform | Actual Result | Evidence | Supported? | Recommended Wording |
|--------------|----------|----------|---------------|----------|------------|---------------------|
| Reduced average end-to-end deployment time by 50% | ~45 min (estimated) | ~15 min (estimated) | NOT MEASURED | Azure subscription unavailable; no actual deployment timing measured | NOT MEASURED | "Automated the full deployment pipeline — a single git push triggers Terraform infrastructure provisioning, image build/push, and container app deployment." |
| Reduced manual deployment steps by 89%, from 9 operator actions to a single Git push | 9 documented steps | 1 git push trigger | 4 of 9 steps automated; 5 operational tasks remain manual | phase7-deploy.ps1 automates steps 1-4 (build, push, manifests, apply). Steps 5-9 (health check, smoke tests, DNS, monitoring, rollback) are NOT automated. | NOT DEFENSIBLE | "Automated the deployment process — a single git push triggers infrastructure provisioning, image build/push, and container app deployment, eliminating 4 manual deployment steps." |
| 96% pipeline success rate across 50+ test runs | N/A | 53 real pipeline runs | 100% (53/53) | reliability-test.md; 53 runs triggered via API, all SUCCESS, all with worker_id=worker-docker, all with job attempts and worker_results | CHANGE NUMBER | "Achieved 100% pipeline success rate across 53 consecutive real pipeline runs in a containerized environment." |
| Improved pipeline throughput by 30% through parallel execution | Sequential: 4 jobs in series | Parallel: BUILD → (TEST ∥ SCAN) → PACKAGE | 22.6% (average) / 33.3% (median) | sequential-vs-parallel-benchmark.md; 10 repetitions each; median is robust to git-clone outliers | CHANGE NUMBER | "Improved pipeline throughput by 33% through DAG-based parallel execution of independent jobs (median of 10 benchmark runs)." |

## Claim Details

### Claim 1: 50% Deployment-Time Reduction — NOT MEASURED

- **Original:** "Reduced average end-to-end deployment time by 50%."
- **Baseline:** ~45 min (estimated from documented steps).
- **Platform:** ~15 min (estimated).
- **Reality:** Azure subscription is unavailable. No actual deployment timing was measured. The 45→15 min figures are estimates, not measurements.
- **Action:** REWORD. Remove the percentage. Describe the implemented automation.

### Claim 2: 89% Manual-Step Reduction — NOT DEFENSIBLE

- **Original:** "Reduced manual deployment steps by 89%, from 9 operator actions to a single Git push."
- **Baseline (9 steps):**
  1. Build Docker images locally — **AUTOMATED** by phase7-deploy.ps1 / GitHub Actions
  2. Push images to ACR — **AUTOMATED**
  3. Update manifests with image tags — **AUTOMATED** (Terraform image_tag variable)
  4. Apply manifests to Azure — **AUTOMATED** (terraform apply)
  5. Verify deployment health — **NOT AUTOMATED** (no health check in workflow)
  6. Run smoke tests — **NOT AUTOMATED** (no smoke tests in workflow)
  7. Update DNS/traffic routing — **NOT AUTOMATED**
  8. Monitor for issues — **NOT AUTOMATED**
  9. Rollback procedure — **NOT AUTOMATED**
- **Analysis:** Only 4 of 9 steps are automated. Steps 5-9 are operational tasks that require human judgment and are not covered by the deployment script or GitHub Actions workflow.
- **Correct calculation:** (9 - 5) / 9 = 44.4% reduction in total manual steps, or (4 - 1) / 4 = 75% reduction in deployment-only steps.
- **Action:** REWORD. The 89% claim counts operational tasks as automated when they are not.

### Claim 3: 96% Success Rate — CHANGE NUMBER to 100%

- **Original:** "96% pipeline success rate across 50+ test runs."
- **Measured:** 53/53 real pipeline runs succeeded (100%).
- **Method:** 53 runs triggered via `POST /api/v1/runs` with the same pipeline version, repository, commit SHA, and branch. Each run executed through the full control-plane → RabbitMQ → worker → result flow.
- **Evidence:** reliability-test.md, reliability-test-50runs.csv, database verification (53 SUCCESS, 0 FAILED, all with worker_id=worker-docker).
- **Action:** CHANGE NUMBER. The measured success rate is 100%, not 96%.

### Claim 4: 30% Throughput Improvement — CHANGE NUMBER to 33%

- **Original:** "Improved pipeline throughput by 30% through parallel execution."
- **Measured:** 22.6% (average) to 33.3% (median) improvement.
- **Method:** 10 repetitions of sequential (BUILD→TEST→SCAN→PACKAGE) and parallel (BUILD→(TEST∥SCAN)→PACKAGE) configurations with identical steps, repository, commit SHA, and worker configuration.
- **Evidence:** sequential-vs-parallel-benchmark.md. The median is the robust measure due to git-clone time variability (outliers on both sides).
- **Action:** CHANGE NUMBER. The median-based improvement is 33.3%.

## Summary

| Claim | Action |
|-------|--------|
| 50% deployment-time reduction | REWORD (remove percentage, describe automation) |
| 89% manual-step reduction | REWORD (only 4 of 9 steps automated) |
| 96% success rate | CHANGE NUMBER → 100% (53/53 measured) |
| 30% throughput improvement | CHANGE NUMBER → 33% (median of 10 runs) |
