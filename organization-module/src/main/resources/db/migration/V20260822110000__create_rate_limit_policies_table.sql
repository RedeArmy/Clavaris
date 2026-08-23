-- ADR-0010 §6.2, BR-ORG-05: the capacity layer's per-Organization aggregate ceiling. A real FK to
-- organizations (unlike signing_keys.organization_id or oauth_clients.organization_id) — both
-- tables are created by this same module's own Flyway migrations, so their relative ordering is
-- guaranteed, none of the cross-module ordering uncertainty those other tables' own migration
-- comments document applies here.
--
-- Absence of a row for a given Organization means "use the system default," not "unlimited" —
-- BR-ORG-06: CreateOrganizationService deliberately never creates one at provisioning time.
CREATE TABLE rate_limit_policies (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      uuid NOT NULL REFERENCES organizations (id),
    requests_per_minute  integer NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

-- One policy per Organization — the "define vs. update in place" distinction
-- SetRateLimitPolicyForOrganizationService itself enforces relies on this being unique.
CREATE UNIQUE INDEX ux_rate_limit_policies_organization_id ON rate_limit_policies (organization_id);
