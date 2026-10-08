-- Clerk "Sessions" settings parity: the four per-Organization session tunables (maximum lifetime,
-- inactivity timeout, reverification window, multi-session handling). A real FK to organizations,
-- created in this same module's own migration sequence — same ordering guarantee
-- rate_limit_policies.organization_id already relies on.
--
-- Absence of a row for a given Organization means "use the system default" (SessionPolicy#defaults),
-- not "unconfigured" — no code path creates one at Organization-provisioning time, same
-- BR-ORG-06-style convention rate_limit_policies already follows.
--
-- All four tunable columns default to SessionPolicy#defaults()'s own values, kept in sync by hand
-- (no single source of truth spans Java and SQL) — maximum_lifetime_minutes/inactivity_timeout_
-- minutes = 10080 (7 days), reverification_window_minutes = 10, multi_session_handling_enabled =
-- true.
CREATE TABLE session_policies (
    id                               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id                  uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    maximum_lifetime_minutes         integer NOT NULL DEFAULT 10080,
    inactivity_timeout_minutes       integer NOT NULL DEFAULT 10080,
    reverification_window_minutes    integer NOT NULL DEFAULT 10,
    multi_session_handling_enabled   boolean NOT NULL DEFAULT true,
    created_at                       timestamptz NOT NULL DEFAULT now(),
    updated_at                       timestamptz NOT NULL DEFAULT now()
);

-- One policy per Organization — the "define vs. update in place" distinction
-- SetSessionPolicyForOrganizationService itself enforces relies on this being unique.
CREATE UNIQUE INDEX ux_session_policies_organization_id ON session_policies (organization_id);
