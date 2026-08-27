-- ADR-0010 §3 addendum (2026-08-27), BR-WS: a team/company grouping *within* one Organization's
-- own isolated account pool. Same-module FK to organizations (both tables owned by
-- organization-module's own Flyway migrations) — ON DELETE CASCADE from the start, same
-- free-cascade precedent V20260826110000 established for rate_limit_policies, so deleting an
-- Organization needs no extra application-layer Workspace-erasure code at all.
--
-- No unique constraint on name: same reasoning organizations.name already documents — not a
-- business rule, and two workspaces within the same Organization could legitimately share a
-- display name.
CREATE TABLE workspaces (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name            varchar(255) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_workspaces_organization_id ON workspaces (organization_id);
