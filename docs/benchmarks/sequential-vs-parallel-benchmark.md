# Sequential vs Parallel Benchmark

**Date:** 2026-09-28
**Repository:** https://github.com/aggaanya/RealShield-Deepfake-Detection-Digital-Identity-Verifier
**Commit SHA:** `39956a86df5ce8955ab02bbc12d6f0036ea1b29b`
**Branch:** main
**Workers:** 1 worker container, `WORKER_MAX_CONCURRENCY=2`
**RabbitMQ:** default config, `pipeline-jobs` queue
**Database:** PostgreSQL 16 (Docker)

## Workload (identical steps, different dependency structure)

| Job | Step | Sleep |
|-----|------|-------|
| BUILD (compile) | `echo BUILD_START && sleep 10 && echo BUILD_DONE` | 10s |
| TEST (unit-test) | `echo TEST_START && sleep 20 && echo TEST_DONE` | 20s |
| SCAN (security-scan) | `echo SCAN_START && sleep 15 && echo SCAN_DONE` | 15s |
| PACKAGE (package) | `echo PACKAGE_START && sleep 5 && echo PACKAGE_DONE` | 5s |

**Sequential:** BUILD → TEST → SCAN → PACKAGE (stage-level dependsOn)
**Parallel:** BUILD → (TEST || SCAN) → PACKAGE (TEST and SCAN in same stage, independent)

## Results (10 repetitions each)

### Sequential

| Rep | Duration (s) |
|-----|-------------|
| 1 | 74.99 |
| 2 | 78.35 |
| 3 | 74.41 |
| 4 | 77.26 |
| 5 | 86.67 |
| 6 | 75.37 |
| 7 | 89.69 |
| 8 | 84.84 |
| 9 | 90.74 |
| 10 | 157.38 |

### Parallel

| Rep | Duration (s) |
|-----|-------------|
| 1 | 204.13 |
| 2 | 51.10 |
| 3 | 50.18 |
| 4 | 56.29 |
| 5 | 52.73 |
| 6 | 56.96 |
| 7 | 61.25 |
| 8 | 56.10 |
| 9 | 49.40 |
| 10 | 50.32 |

## Statistics

| Metric | Sequential | Parallel |
|--------|-----------|----------|
| Average | 88.97s | 68.85s |
| Median | 81.60s | 54.42s |
| Min | 74.41s | 49.40s |
| Max | 157.38s | 204.13s |

## Improvement

- **Average-based:** ((88.97 - 68.85) / 88.97) × 100 = **22.6%**
- **Median-based:** ((81.60 - 54.42) / 81.60) × 100 = **33.3%**

## Notes on Variability

The git clone time per job is highly variable (5s–166s) due to network conditions
when cloning from GitHub. This affects both configurations. The sequential
configuration is more sensitive because it has 4 sequential clones; the parallel
configuration overlaps the TEST and SCAN clones, making it more robust to a slow
clone on one branch.

The median is the more robust measure here because both configurations have
outliers caused by slow git clones. The median-based improvement is **33.3%**.

All 10 parallel runs completed successfully. All 10 sequential runs completed
successfully. No runs failed.
