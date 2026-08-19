-- data-model.md §2, ADR-0010 (Organization provisioning), BR-PLATFORM-01/02/03. Deliberately NO
-- organization_id column, not even nullable — a PlatformClient authenticates the operations that
-- create/manage Organization rows themselves, so making it belong to one would be circular and
-- would reopen the exact cross-tenant blast-radius risk ADR-0010 closes for everything else.
CREATE TABLE platform_clients (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id          varchar(255) NOT NULL,
    client_secret_hash varchar(255) NOT NULL,
    allowed_scopes     text NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_platform_clients_client_id ON platform_clients (client_id);
