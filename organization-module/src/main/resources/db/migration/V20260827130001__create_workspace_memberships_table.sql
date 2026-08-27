-- ADR-0010 §3 addendum (2026-08-27), BR-WS-04/05: links an identity-module Account to one
-- Workspace, with a Clavaris-internal role (application-level enum, ADMIN|MEMBER — no DB check
-- constraint, same "enforced at the application layer" posture RateLimitPolicy/Organization
-- already use for their own domain rules).
--
-- account_id deliberately has NO foreign key: it references identity-module's own accounts table,
-- and DeleteOrganizationService's own Javadoc already documents why a cross-module FK doesn't work
-- in this codebase (each module's Testcontainers-backed test suite only scans its own db/migration
-- folder — a migration here referencing identity-module's table fails this module's own isolated
-- tests with "relation does not exist"). Cross-module referential integrity is enforced at the
-- application layer instead: WorkspaceMembershipEraserBridge (app module), called synchronously
-- from identity-module's DeleteAccountService before an Account row disappears (ADR-0007).
--
-- workspace_id DOES cascade at the DB level: workspaces is this same module's own table.
CREATE TABLE workspace_memberships (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    account_id   uuid NOT NULL,
    role         varchar(20) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- One membership row per (workspace, account) — AddWorkspaceMemberService relies on this being
-- unique to reject a double-add of the same Account to the same Workspace.
CREATE UNIQUE INDEX ux_workspace_memberships_workspace_id_account_id
    ON workspace_memberships (workspace_id, account_id);

-- WorkspaceMembershipEraserBridge's own query shape (BR-DATA-02/03's Workspace-side cascade).
CREATE INDEX ix_workspace_memberships_account_id ON workspace_memberships (account_id);
