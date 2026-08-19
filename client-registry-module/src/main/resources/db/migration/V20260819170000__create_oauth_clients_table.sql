-- data-model.md §2, ADR-0010, BR-ORG-02: a consuming application's protocol registration, scoped
-- to exactly one Organization — the actual mechanism that keeps one tenant's login flow from ever
-- authenticating another tenant's accounts. organization_id has no FK constraint to organizations
-- here: same cross-module migration-ordering reasoning as signing_keys' own migration (this
-- module's Flyway migrations and organization-module's are not guaranteed to run in a fixed
-- relative order) — the application layer (OrganizationExistsChecker) is the sole enforcement
-- point, not the database.
CREATE TABLE oauth_clients (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      uuid NOT NULL,
    client_id            varchar(255) NOT NULL,
    client_secret_hash   varchar(255) NOT NULL,
    redirect_uris        text NOT NULL,
    allowed_grant_types  text NOT NULL,
    allowed_scopes       text NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now()
);

-- data-model.md §3: client lookup at /authorize and /token is global-by-client_id (the row's own
-- organization_id is what scopes it to a tenant, not a compound key).
CREATE UNIQUE INDEX ux_oauth_clients_client_id ON oauth_clients (client_id);

-- List a tenant's registered clients.
CREATE INDEX ix_oauth_clients_organization_id ON oauth_clients (organization_id);
