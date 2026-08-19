-- data-model.md §2, ADR-0010 (Organization provisioning). Deliberately no organization_id — same
-- reasoning as platform_clients (client-registry-module): the platform issuer signs its own
-- tokens with its own key, structurally separate from signing_keys (per-Organization, added by
-- the CreateOrganization slice).
CREATE TABLE platform_signing_keys (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kid         varchar(255) NOT NULL,
    algorithm   varchar(20) NOT NULL,
    active_from timestamptz NOT NULL DEFAULT now(),
    retired_at  timestamptz
);

CREATE UNIQUE INDEX ux_platform_signing_keys_kid ON platform_signing_keys (kid);

-- Active-key lookup at token-issuance/JWKS time — same partial-index pattern data-model.md §3
-- documents for the per-Organization signing_keys table.
CREATE INDEX ix_platform_signing_keys_active ON platform_signing_keys (id) WHERE retired_at IS NULL;
