-- ADR-0010, BR-DATA-02/03's own organization-level equivalent — see identity-module's own sibling
-- migration (same version) for the full cross-module context. rate_limit_policies already had a
-- real FK to organizations (V20260822110000 — both tables are this module's own, no cross-module
-- ordering concern ever applied here), just not ON DELETE CASCADE. Constraint name confirmed live
-- against a disposable Postgres instance (Postgres's own default <table>_<column>_fkey naming —
-- the original migration never named it explicitly), not assumed.
ALTER TABLE rate_limit_policies DROP CONSTRAINT rate_limit_policies_organization_id_fkey;
ALTER TABLE rate_limit_policies
    ADD CONSTRAINT rate_limit_policies_organization_id_fkey
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE;
