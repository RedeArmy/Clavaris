-- SDE-III review, 2026-09-19 — Clerk "Restrictions" parity, minimal: a per-Organization
-- blocklist/allowlist of exact emails or "@domain.tld" patterns. Real FK + ON DELETE CASCADE —
-- organization-module owns both this table and organizations itself, same precedent
-- rate_limit_policies/account_authentication_policies already establish.
--
-- UNIQUE(organization_id, identifier), not (organization_id, type, identifier) — an identifier
-- being on both lists at once for the same Organization is a contradiction, not a valid state, so
-- the constraint rules it out structurally rather than relying on application-layer discipline.
CREATE TABLE access_restriction_entries (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    type              varchar(10) NOT NULL,
    identifier        varchar(320) NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_access_restriction_entries_organization_id_identifier
    ON access_restriction_entries (organization_id, identifier);
