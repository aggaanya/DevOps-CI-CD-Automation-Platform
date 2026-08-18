-- V1: Create the core CI/CD domain schema
-- All tables use UUID primary keys, snake_case naming, UTC timestamps.

-- ============================================================
-- 1. organizations
-- ============================================================
CREATE TABLE organizations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(255) NOT NULL,
    description     TEXT,
    status          VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_organizations_slug UNIQUE (slug),
    CONSTRAINT ck_organizations_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED'))
);

CREATE INDEX idx_organizations_status ON organizations (status);

-- ============================================================
-- 2. projects
-- ============================================================
CREATE TABLE projects (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(255) NOT NULL,
    description     TEXT,
    status          VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_projects_org_slug UNIQUE (organization_id, slug),
    CONSTRAINT ck_projects_status CHECK (status IN ('ACTIVE', 'ARCHIVED', 'SUSPENDED'))
);

CREATE INDEX idx_projects_organization_id ON projects (organization_id);
CREATE INDEX idx_projects_status ON projects (status);

-- ============================================================
-- 3. repositories
-- ============================================================
CREATE TABLE repositories (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id        UUID NOT NULL REFERENCES projects(id) ON DELETE RESTRICT,
    provider          VARCHAR(50) NOT NULL,
    repository_url    VARCHAR(1024) NOT NULL,
    repository_name   VARCHAR(255) NOT NULL,
    default_branch    VARCHAR(255) NOT NULL DEFAULT 'main',
    webhook_id        VARCHAR(255),
    status            VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_repositories_project_url UNIQUE (project_id, repository_url),
    CONSTRAINT ck_repositories_provider CHECK (provider IN ('GITHUB', 'GITLAB', 'BITBUCKET')),
    CONSTRAINT ck_repositories_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'PENDING'))
);

CREATE INDEX idx_repositories_project_id ON repositories (project_id);
CREATE INDEX idx_repositories_status ON repositories (status);

-- ============================================================
-- 4. pipelines
-- ============================================================
CREATE TABLE pipelines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE RESTRICT,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    status          VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_pipelines_project_name UNIQUE (project_id, name),
    CONSTRAINT ck_pipelines_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED'))
);

CREATE INDEX idx_pipelines_project_id ON pipelines (project_id);
CREATE INDEX idx_pipelines_status ON pipelines (status);

-- ============================================================
-- 5. pipeline_versions
-- ============================================================
CREATE TABLE pipeline_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id     UUID NOT NULL REFERENCES pipelines(id) ON DELETE RESTRICT,
    version         INTEGER NOT NULL,
    yaml_content    TEXT NOT NULL,
    commit_sha      VARCHAR(40),
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_pipeline_versions_pipeline_version UNIQUE (pipeline_id, version)
);

CREATE INDEX idx_pipeline_versions_pipeline_id ON pipeline_versions (pipeline_id);
CREATE INDEX idx_pipeline_versions_commit_sha ON pipeline_versions (commit_sha);

-- ============================================================
-- 6. pipeline_runs
-- ============================================================
CREATE TABLE pipeline_runs (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_version_id   UUID NOT NULL REFERENCES pipeline_versions(id) ON DELETE RESTRICT,
    repository_id         UUID REFERENCES repositories(id) ON DELETE SET NULL,
    commit_sha            VARCHAR(40) NOT NULL,
    branch                VARCHAR(255) NOT NULL,
    trigger_type          VARCHAR(50) NOT NULL,
    triggered_by          VARCHAR(255),
    status                VARCHAR(50) NOT NULL DEFAULT 'QUEUED',
    started_at            TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_pipeline_runs_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_pipeline_runs_trigger CHECK (trigger_type IN ('MANUAL', 'WEBHOOK', 'SCHEDULED', 'API'))
);

CREATE INDEX idx_pipeline_runs_pipeline_version_id ON pipeline_runs (pipeline_version_id);
CREATE INDEX idx_pipeline_runs_repository_id ON pipeline_runs (repository_id);
CREATE INDEX idx_pipeline_runs_status ON pipeline_runs (status);
CREATE INDEX idx_pipeline_runs_commit_sha ON pipeline_runs (commit_sha);
CREATE INDEX idx_pipeline_runs_created_at ON pipeline_runs (created_at DESC);

-- ============================================================
-- 7. pipeline_stages
-- ============================================================
CREATE TABLE pipeline_stages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_run_id UUID NOT NULL REFERENCES pipeline_runs(id) ON DELETE RESTRICT,
    name            VARCHAR(255) NOT NULL,
    order_index     INTEGER NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,

    CONSTRAINT uq_pipeline_stages_run_order UNIQUE (pipeline_run_id, order_index),
    CONSTRAINT ck_pipeline_stages_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED'))
);

CREATE INDEX idx_pipeline_stages_pipeline_run_id ON pipeline_stages (pipeline_run_id);

-- ============================================================
-- 8. pipeline_jobs
-- ============================================================
CREATE TABLE pipeline_jobs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_stage_id   UUID NOT NULL REFERENCES pipeline_stages(id) ON DELETE RESTRICT,
    name                VARCHAR(255) NOT NULL,
    job_type            VARCHAR(50) NOT NULL,
    status              VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    worker_id           VARCHAR(255),
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    exit_code           INTEGER,

    CONSTRAINT ck_pipeline_jobs_status CHECK (status IN ('PENDING', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_pipeline_jobs_type CHECK (job_type IN ('BUILD', 'TEST', 'SCAN', 'DEPLOY', 'PACKAGE', 'CUSTOM'))
);

CREATE INDEX idx_pipeline_jobs_pipeline_stage_id ON pipeline_jobs (pipeline_stage_id);
CREATE INDEX idx_pipeline_jobs_status ON pipeline_jobs (status);

-- ============================================================
-- 9. job_attempts
-- ============================================================
CREATE TABLE job_attempts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES pipeline_jobs(id) ON DELETE RESTRICT,
    attempt_number  INTEGER NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    exit_code       INTEGER,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    logs_location   VARCHAR(1024),

    CONSTRAINT uq_job_attempts_job_attempt UNIQUE (job_id, attempt_number),
    CONSTRAINT ck_job_attempts_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED'))
);

CREATE INDEX idx_job_attempts_job_id ON job_attempts (job_id);

-- ============================================================
-- 10. webhook_events
-- ============================================================
CREATE TABLE webhook_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider        VARCHAR(50) NOT NULL,
    delivery_id     VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    repository_id   UUID REFERENCES repositories(id) ON DELETE SET NULL,
    payload         JSONB,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    status          VARCHAR(50) NOT NULL DEFAULT 'RECEIVED',
    error_message   TEXT,

    CONSTRAINT uq_webhook_events_provider_delivery UNIQUE (provider, delivery_id),
    CONSTRAINT ck_webhook_events_status CHECK (status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'REJECTED', 'FAILED'))
);

CREATE INDEX idx_webhook_events_repository_id ON webhook_events (repository_id);
CREATE INDEX idx_webhook_events_status ON webhook_events (status);
CREATE INDEX idx_webhook_events_received_at ON webhook_events (received_at DESC);

-- ============================================================
-- 11. outbox_events
-- ============================================================
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(255) NOT NULL,
    aggregate_type  VARCHAR(255) NOT NULL,
    aggregate_id    UUID NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    error_message   TEXT,

    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_outbox_events_status ON outbox_events (status);
CREATE INDEX idx_outbox_events_created_at ON outbox_events (created_at ASC);
CREATE INDEX idx_outbox_events_pending ON outbox_events (created_at ASC) WHERE status = 'PENDING';

-- ============================================================
-- 12. artifacts
-- ============================================================
CREATE TABLE artifacts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_run_id   UUID NOT NULL REFERENCES pipeline_runs(id) ON DELETE RESTRICT,
    job_id            UUID REFERENCES pipeline_jobs(id) ON DELETE SET NULL,
    artifact_type     VARCHAR(50) NOT NULL,
    name              VARCHAR(255) NOT NULL,
    location_url      VARCHAR(1024) NOT NULL,
    image_digest      VARCHAR(255),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_artifacts_type CHECK (artifact_type IN ('DOCKER_IMAGE', 'MAVEN_JAR', 'NPM_PACKAGE', 'GENERIC'))
);

CREATE INDEX idx_artifacts_pipeline_run_id ON artifacts (pipeline_run_id);

-- ============================================================
-- 13. deployments
-- ============================================================
CREATE TABLE deployments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_run_id   UUID NOT NULL REFERENCES pipeline_runs(id) ON DELETE RESTRICT,
    environment       VARCHAR(100) NOT NULL,
    image_digest      VARCHAR(255),
    status            VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    endpoint          VARCHAR(1024),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_deployments_status CHECK (status IN ('PENDING', 'DEPLOYING', 'SUCCESS', 'FAILED', 'ROLLED_BACK'))
);

CREATE INDEX idx_deployments_pipeline_run_id ON deployments (pipeline_run_id);
CREATE INDEX idx_deployments_environment ON deployments (environment);

-- ============================================================
-- 14. audit_events
-- ============================================================
CREATE TABLE audit_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor           VARCHAR(255) NOT NULL,
    action          VARCHAR(255) NOT NULL,
    resource_type   VARCHAR(255) NOT NULL,
    resource_id     UUID NOT NULL,
    metadata        JSONB,
    correlation_id  VARCHAR(255),
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(512),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_resource ON audit_events (resource_type, resource_id);
CREATE INDEX idx_audit_events_created_at ON audit_events (created_at DESC);
CREATE INDEX idx_audit_events_correlation_id ON audit_events (correlation_id);
CREATE INDEX idx_audit_events_actor ON audit_events (actor);
