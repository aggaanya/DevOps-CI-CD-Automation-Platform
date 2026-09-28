-- Persist the resolved pipeline DAG for a run.
--
-- Previously, stage/job dependencies were re-derived from
-- pipeline_versions.yaml_content on every dispatch cycle. This migration makes
-- the resolved DAG a first-class, immutable property of a run so the scheduler
-- never has to re-parse YAML mid-execution and so a run remains auditable
-- after the fact (benchmark extraction, incident review).
--
-- The column holds a comma-separated list of lower-cased dependency names:
--   * pipeline_stages.depends_on -> lower-cased pipeline_stages.name values
--   * pipeline_jobs.depends_on    -> lower-cased pipeline_jobs.name values in the same stage
--
-- Nullable with no default so that runs created before this migration keep
-- working unchanged. When NULL the scheduler falls back to the previous
-- behaviour (re-derive from the immutable version YAML), which is also what
-- makes historical runs such as 5ddd4563-ab23-4eee-bf51-033b37c08f8f
-- reproducible.

ALTER TABLE pipeline_stages
    ADD COLUMN depends_on VARCHAR(1024);

ALTER TABLE pipeline_jobs
    ADD COLUMN depends_on VARCHAR(1024);

-- Scheduler hot path: "which jobs of this stage are still dispatchable".
CREATE INDEX idx_pipeline_jobs_stage_status
    ON pipeline_jobs (pipeline_stage_id, status);

-- Run completion check: "are all stages of this run terminal yet".
CREATE INDEX idx_pipeline_stages_run_status
    ON pipeline_stages (pipeline_run_id, status);
