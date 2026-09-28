# E2E Validation: Control Plane → RabbitMQ → Worker → Result

**Date:** 2026-09-28
**Run ID:** `34c6a218-7465-4fa7-9d2f-d8a672b3d2c7`
**PipelineVersion:** `30980a3a-cc18-4a17-86a3-5c8432432745` (version 6, "e2e-validation")
**Repository:** https://github.com/aggaanya/RealShield-Deepfake-Detection-Digital-Identity-Verifier
**Commit SHA:** `39956a86df5ce8955ab02bbc12d6f0036ea1b29b` (verified via `git ls-remote`)
**Branch:** main

## Flow Verified

| Step | Evidence | Status |
|------|----------|--------|
| PipelineVersion created via API | `POST /api/v1/pipelines/{id}/versions` → version 6 | PASS |
| Run triggered via API | `POST /api/v1/runs` → run `34c6a218-...`, status RUNNING | PASS |
| DAG scheduler dispatched job | backend log `[JOB_DISPATCHED] jobId=42619ef6-...` | PASS |
| RabbitMQ `pipeline-jobs` consumed | worker log `Acknowledged job 42619ef6-... from pipeline-jobs` | PASS |
| Git checkout at commit SHA | worker log `Checked out commit 39956a86df5ce8955ab02bbc12d6f0036ea1b29b` | PASS |
| Declared steps executed (not pipeline.yml) | worker log `mode=control-plane`, `Starting step [step-1] (RUN): echo E2E_BUILD_DONE` | PASS |
| Worker result published | worker log `Published result for job 42619ef6-... with status SUCCESS` | PASS |
| Control plane recorded result | `worker_results` row: worker_id=`worker-docker`, duration=13795ms | PASS |
| Run state advanced by orchestrator | backend log `[RUN_COMPLETED] runId=34c6a218-..., status=SUCCESS` | PASS |
| Final run status | **SUCCESS** (finishedAt 12:13:55) | PASS |

## Database Evidence

```
pipeline_jobs:      status=SUCCESS, worker_id=worker-docker, exit_code=0
job_attempts:       attempt 1, status=SUCCESS, exit_code=0
pipeline_stages:    build, status=SUCCESS
worker_results:      worker_id=worker-docker, duration_ms=13795, commit_sha=39956a86...
pipeline_runs:      status=SUCCESS
```

## Key Architecture Fix Validated

The worker received the declared steps from the PipelineVersion (control-plane mode),
NOT from a pipeline.yml in the repository. The repository was used only for source
code checkout at the exact commit SHA. This is the intended separation.

## Files

- `e2e-run-34c6a218-backend.log` — backend log excerpt
- `e2e-run-34c6a218-worker.log` — worker log excerpt
