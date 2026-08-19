-- data-model.md §2, BR-ORG-04: every Organization owns its own RS256 signing key pair, structurally
-- separate from platform_signing_keys (client-registry-module's PlatformClient counterpart lives
-- there too, ADR-0010 Organization provisioning). organization_id has no FK constraint to
-- organizations here: organization-module's own migration (V20260818170300) may run before or
-- after this one within the same Flyway history depending on module scan order, and Flyway does
-- not guarantee cross-module ordering beyond the version number itself — a real FK is deferred
-- until both tables are confirmed to always migrate in a fixed order; the application layer is
-- the sole writer of this column today (identity-module never accepts an arbitrary organization_id
-- from a caller).
CREATE TABLE signing_keys (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    kid             varchar(255) NOT NULL,
    algorithm       varchar(20) NOT NULL,
    active_from     timestamptz NOT NULL DEFAULT now(),
    retired_at      timestamptz
);

-- data-model.md §3: kid is only unique within an Organization, not globally (ADR-0010 §5).
CREATE UNIQUE INDEX ux_signing_keys_organization_id_kid ON signing_keys (organization_id, kid);

-- Active-key lookup for token signing/JWKS at issuance time, scoped to one Organization.
CREATE INDEX ix_signing_keys_organization_id_active ON signing_keys (organization_id) WHERE retired_at IS NULL;
