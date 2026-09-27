-- ADR-0027: replaces the fixed ADMIN|MEMBER enum (ADR-0010 §3 addendum, now superseded) with a
-- real, Organization-scoped entity — one role definition set is shared across every Workspace an
-- Organization owns, not created fresh per Workspace.
--
-- permissions is stored as text (JSON array) — same convention as webhook_endpoints'
-- subscribed_event_types and oauth_clients' allowed_scopes: an opaque, consumer-defined,
-- unbounded-cardinality string set with no domain-enforced catalog, serialized/deserialized via
-- ObjectMapper at the repository adapter layer, not a join table.
--
-- organization_id DOES cascade at the DB level: organizations is this same module's own table.
-- parent_role_id is a self-reference (ADR-0027 §3, optional hierarchy) — no ON DELETE action
-- specified deliberately: deleting a role that's still some other role's parent must be rejected
-- at the application layer first (same "explicit, no implicit cascade" posture ADR-0027 §5
-- documents for member reassignment), so the default RESTRICT is exactly the behavior wanted here.
CREATE TABLE workspace_roles (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name            varchar(255) NOT NULL,
    parent_role_id  uuid REFERENCES workspace_roles (id),
    permissions     text NOT NULL,
    reserved        boolean NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- A consumer's own role names are opaque to Clavaris but still scoped for uniqueness within their
-- own Organization — same "one namespace per tenant" convention OAuthClient's own client_id
-- uniqueness already follows at a different tier.
CREATE UNIQUE INDEX ux_workspace_roles_organization_id_name
    ON workspace_roles (organization_id, name);

-- CreateWorkspaceService's own ensureReservedRoleExists lookup, and ManageMembersGuard/
-- WorkspaceRoleHierarchy's own findAllByOrganizationId — both filter or scan by organization_id.
CREATE INDEX ix_workspace_roles_organization_id ON workspace_roles (organization_id);
