# Failure Propagation Verification

**Date:** 2026-09-28
**Run ID:** `3d2e65b5-37b1-4ae7-87a4-d386dc4f0820`
**PipelineVersion:** `5e19de52-b06c-4689-b6d1-8f02d6117f34` ("failure-propagation")

## Pipeline Structure

```
BUILD (compile, exit 0)
  ↓
TEST (unit-test, exit 1)  ← fails
  ↓
SCAN (security-scan, exit 0)
  ↓
PACKAGE (package, exit 0)
```

## Expected vs Actual

| Job | Expected | Actual | Status |
|-----|----------|--------|--------|
| compile (BUILD) | SUCCESS | SUCCESS | PASS |
| unit-test (TEST) | FAILURE | FAILED (after retry exhaustion) | PASS |
| security-scan (SCAN) | SKIPPED | SKIPPED (blockedBy=test/unit-test) | PASS |
| package (PACKAGE) | SKIPPED | SKIPPED (blockedBy=quality/security-scan) | PASS |
| **RUN** | **FAILED** | **FAILED** | **PASS** |

## Backend Log Evidence

```
[JOB_SKIPPED] jobName=security-scan, blockedBy=test/unit-test
[JOB_SKIPPED] jobName=package, blockedBy=quality/security-scan
[RUN_COMPLETED] runId=3d2e65b5-37b1-4ae7-87a4-d386dc4f0820, status=FAILED
```

## Key Observations

1. **TEST failed** after the worker executed `echo TEST && exit 1` (exit code 1).
2. **Retry mechanism** re-dispatched TEST for attempt 2 (after the DuplicateJobGuard
   was fixed to key on jobId+attemptNumber). Attempt 2 also failed.
3. **Retry exhausted** after 2 attempts (maxRetries=3, but `shouldRetry` allows
   attempts.size()+1 < maxRetries, so 2 total attempts).
4. **SCAN was skipped** — it was in the `quality` stage which depends on `test`.
   Since `test` failed, `quality` was blocked and SCAN was marked SKIPPED.
5. **PACKAGE was skipped** — it was in the `package` stage which depends on `quality`.
   Since `quality` was skipped, `package` was blocked and PACKAGE was marked SKIPPED.
6. **Run reached terminal FAILED state** — no jobs remained PENDING forever.

## Bugs Found and Fixed During Verification

1. **DuplicateJobGuard** keyed only on `jobId`, causing retries to be skipped as
   duplicates. Fixed to key on `jobId + attemptNumber`.
2. **WorkerResultConsumer** used `existsByJobId` which blocked retry results
   (same jobId, different attempt). Fixed to use `existsByJobIdAndStatusAndDurationMs`
   so only true duplicates (same jobId, status, and duration) are skipped.
