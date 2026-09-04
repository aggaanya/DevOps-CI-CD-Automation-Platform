-- V3: Record structured results published by standalone workers (Phase 5/7
-- integration seam). Each row is one PipelineResult received from a worker
-- via the cicd.results queue. jobId is the worker-side job identifier and is
-- intentionally NOT foreign-keyed to pipeline_jobs: directly published jobs
-- are not reconciable with the control-plane domain model.

CREATE TABLE worker_results (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          VARCHAR(255) NOT NULL,
    pipeline_id     VARCHAR(255),
    status          VARCHAR(50) NOT NULL,
    worker_id       VARCHAR(255) NOT NULL,
    repository_url  VARCHAR(1024),
    commit_sha      VARCHAR(40),
    branch          VARCHAR(255),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    duration_ms     BIGINT,
    message         TEXT,
    payload         JSONB NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_worker_results_status CHECK (status IN (
        'PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED', 'TIMED_OUT'))
);

CREATE INDEX idx_worker_results_job_id ON worker_results (job_id);
CREATE INDEX idx_worker_results_status ON worker_results (status);
CREATE INDEX idx_worker_results_received_at ON worker_results (received_at DESC);
CREATE INDEX idx_worker_results_completed_at ON worker_results (completed_at DESC);