# Webhook → RabbitMQ → Worker Verification

**Date:** 2026-09-28
**Repository:** https://github.com/aggaanya/RealShield-Deepfake-Detection-Digital-Identity-Verifier
**Commit SHA:** `39956a86df5ce8955ab02bbc12d6f0036ea1b29b`

## Test 1: Valid Webhook → Pipeline Run

A GitHub push webhook with a valid HMAC-SHA256 signature was sent to
`POST /api/v1/webhooks/github?repositoryId=425d9664-...`.

- **Result:** 202 Accepted, 10 pipeline runs triggered (one per pipeline version).
- **Runs:** 5 completed SUCCESS, 5 RUNNING (at time of check).
- **Flow:** webhook → HMAC verification → delivery ID dedup → run creation →
  DAG scheduler → RabbitMQ `pipeline-jobs` → worker → result → control plane.

## Test 2: Invalid Signature → Rejected

Same payload with `X-Hub-Signature-256: sha256=invalid...`.

- **Result:** 403 Forbidden. No run created.

## Test 3: Duplicate Delivery ID → Idempotent

Same payload and signature as Test 1 (delivery ID `webhook-test-001`).

- **Result:** 202 Accepted, status=PROCESSED. No new runs created.
- **Run count:** remained 10 (no duplicates).

## Security Verification

| Test | Expected | Actual | Status |
|------|----------|--------|--------|
| Valid signature | 202 Accepted | 202 Accepted | PASS |
| Invalid signature | 403 Forbidden | 403 Forbidden | PASS |
| Duplicate delivery ID | No duplicate runs | No duplicate runs | PASS |

The webhook secret (`benchmark-secret`) is enforced. Invalid signatures are
rejected. Delivery IDs are idempotent.
