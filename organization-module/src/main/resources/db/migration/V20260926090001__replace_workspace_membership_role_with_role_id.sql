-- ADR-0027: replaces the fixed ADMIN|MEMBER enum column with a nullable FK into the new
-- workspace_roles table. Clean-break replacement, not an additive/deprecation-period column: no
-- production tenant depends on the enum shape yet (CLAUDE.md §11 — JobSeeker hasn't integrated
-- against a deployed instance). role_id is nullable by design (ADR-0027 §5): a membership with no
-- role assigned is an explicitly allowed state, not a data-integrity gap.
--
-- No ON DELETE action on role_id deliberately: a WorkspaceRole still referenced by a membership
-- cannot be deleted at all (ADR-0027 §5, enforced at the application layer before the delete is
-- even attempted), so the default RESTRICT here is a second, DB-level backstop for that same
-- invariant, not a new one.
ALTER TABLE workspace_memberships
    DROP COLUMN role,
    ADD COLUMN role_id uuid REFERENCES workspace_roles (id);
