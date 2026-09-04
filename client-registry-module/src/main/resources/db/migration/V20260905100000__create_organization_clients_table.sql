-- ADR-0023 (per-Organization admin credential, Clerk "Secret Key" parity): mirrors platform_clients
-- (V20260818170000 + V20260823100000) exactly, plus organization_id. Same "no FK constraint to
-- organizations" reasoning as oauth_clients' own migration (V20260819170000) — client-registry-
-- module's and organization-module's Flyway migrations are not guaranteed to run in a fixed
-- relative order, so the application layer (OrganizationExistsChecker, already used by
-- RegisterOAuthClientService) is the sole enforcement point, not the database, same precedent
-- exactly.
CREATE TABLE organization_clients (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    uuid NOT NULL,
    client_id          varchar(255) NOT NULL,
    client_secret_hash varchar(255) NOT NULL,
    allowed_scopes     text NOT NULL,
    active             boolean NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_organization_clients_client_id ON organization_clients (client_id);
CREATE INDEX ix_organization_clients_organization_id ON organization_clients (organization_id);
