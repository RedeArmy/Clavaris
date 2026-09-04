-- ADR-0022 (amending ADR-0020 Decision 4): a PRODUCTION Organization's own Google/GitHub OAuth app
-- credentials, opted into on top of the existing social_login_enabled/allowed_social_providers gate.
-- Real FK + ON DELETE CASCADE from day one — organization-module owns both this table and
-- organizations itself, so, unlike webhook_endpoints (a different module, no guaranteed migration
-- ordering), there is no cross-module Flyway-ordering constraint here.
CREATE TABLE organization_social_credentials (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id          uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    provider                 varchar(20) NOT NULL,
    client_id                varchar(255) NOT NULL,
    client_secret_encrypted  text NOT NULL,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, provider)
);
