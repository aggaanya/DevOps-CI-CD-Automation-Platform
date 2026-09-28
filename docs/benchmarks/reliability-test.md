# 50+ Pipeline Reliability Test

**Date:** 2026-09-28
**PipelineVersion:** `30980a3a-cc18-4a17-86a3-5c8432432745` (version 6, "e2e-validation")
**Repository:** https://github.com/aggaanya/RealShield-Deepfake-Detection-Digital-Identity-Verifier
**Commit SHA:** `39956a86df5ce8955ab02bbc12d6f0036ea1b29b`
**Branch:** main
**Worker:** worker-docker (WORKER_MAX_CONCURRENCY=2)

## Method

50 pipeline runs were triggered through the real API (`POST /api/v1/runs`) with the
same pipeline version, repository, commit SHA, and branch. Each run executed a
single job (compile) that:
1. Received declared steps from the PipelineVersion via RabbitMQ `pipeline-jobs`
2. Cloned the repository at the exact commit SHA
3. Executed the declared step (`echo E2E_BUILD_DONE`)
4. Published the result to `cicd.results`
5. The control plane recorded the result and advanced the run state

10 additional runs were attempted; 3 succeeded (total 53), 7 failed due to a
client-side token expiration in the test script (not a platform failure).

## Results

| Metric | Value |
|--------|-------|
| Total runs completed | 53 |
| Successful | 53 |
| Failed | 0 |
| **Success rate** | **100%** |

## Database Verification

```sql
SELECT count(*) AS runs, count(DISTINCT pj.id) AS jobs_executed,
       count(DISTINCT ja.id) AS attempts, count(DISTINCT wr.id) AS worker_results
FROM pipeline_runs pr
LEFT JOIN pipeline_stages ps ON ps.pipeline_run_id = pr.id
LEFT JOIN pipeline_jobs pj ON pj.pipeline_stage_id = ps.id
LEFT JOIN job_attempts ja ON ja.job_id = pj.id
LEFT JOIN worker_results wr ON wr.job_id::text = pj.id::text
WHERE pr.triggered_by = 'reliability-test';
```

Result: `runs=53, jobs_executed=53, attempts=53, worker_results=53`

All 53 runs were executed by `worker-docker` (the standalone worker container).
Every run has a corresponding job, job attempt, and worker result row.

## Files

- `reliability-test-50runs.csv` — per-run results
