-- data-model.md §2: deliberately a separate table from accounts, never a nullable password_hash
-- column bolted onto it — keeps BR-ID-02 (never zero auth methods) a natural COUNT-across-tables
-- query rather than a null-check special case.
CREATE TABLE password_credentials (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    uuid NOT NULL REFERENCES accounts (id),
    password_hash varchar(255) NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- One password credential per account in v1 (registration attaches exactly one, and there is no
-- password-change flow yet that would ever need a second row to exist simultaneously).
CREATE UNIQUE INDEX ux_password_credentials_account_id ON password_credentials (account_id);
