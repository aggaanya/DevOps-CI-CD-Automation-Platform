-- Introduce SKIPPED as a terminal PipelineJob state.
--
-- The DAG scheduler distinguishes three ways a job can end up not having run:
--   SUCCESS   - it ran and passed
--   FAILED    - it ran and failed
--   CANCELLED - the operator cancelled the run
--   SKIPPED   - it was never dispatched because an upstream dependency reached a
--               terminal non-successful state
--
-- Without SKIPPED, a dependent job of a failed branch stays PENDING forever, its
-- stage never becomes terminal, and the run never reaches a terminal status. That
-- is the failure mode this migration fixes: failure propagation has to end in a
-- state, not in silence.
--
-- The status column is a VARCHAR with an explicit CHECK constraint, so the new
-- value has to be added to that constraint before the application can write it.

ALTER TABLE pipeline_jobs DROP CONSTRAINT ck_pipeline_jobs_status;

ALTER TABLE pipeline_jobs ADD CONSTRAINT ck_pipeline_jobs_status
    CHECK (status::text = ANY (ARRAY[
        'PENDING'::character varying,
        'QUEUED'::character varying,
        'RUNNING'::character varying,
        'SUCCESS'::character varying,
        'FAILED'::character varying,
        'CANCELLED'::character varying,
        'SKIPPED'::character varying
    ]::text[]));

-- Scheduler hot path for failure propagation: "find the PENDING jobs of a run".
CREATE INDEX idx_pipeline_jobs_stage_status_pending
    ON pipeline_jobs (pipeline_stage_id, status)
    WHERE status = 'PENDING';
