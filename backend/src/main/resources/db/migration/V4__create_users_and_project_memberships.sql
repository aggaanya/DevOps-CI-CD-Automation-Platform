-- Phase 9 — Authentication & RBAC support.
--
-- platform_user        : identity rows back-filling Keycloak subjects (the
--                        immutable `sub` claim) so project membership can be
--                        modelled and audited without relying on mutable
--                        usernames or emails.
-- project_membership   : per-project grants (ADMIN / DEVELOPER / VIEWER).
--                        Global ADMIN users bypass membership checks.

CREATE TABLE platform_user (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_subject VARCHAR(255) NOT NULL UNIQUE,
    username         VARCHAR(255) NOT NULL,
    email            VARCHAR(255),
    display_name     VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_user_subject ON platform_user (keycloak_subject);

CREATE TABLE project_membership (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES platform_user (id) ON DELETE CASCADE,
    role       VARCHAR(50) NOT NULL CHECK (role IN ('ADMIN', 'DEVELOPER', 'VIEWER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_membership_project_user UNIQUE (project_id, user_id)
);

CREATE INDEX idx_project_membership_project ON project_membership (project_id);
CREATE INDEX idx_project_membership_user ON project_membership (user_id);