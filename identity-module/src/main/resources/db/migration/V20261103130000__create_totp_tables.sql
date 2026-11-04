-- ADR-0040 (TD-FUT-056): an authenticator-app second factor. ON DELETE CASCADE from the first
-- migration, the lesson known_devices and webauthn_credentials already record.
-- secret_encrypted is AES-GCM ciphertext (the server must read the secret back to check a code, so
-- it cannot be hashed); confirmed_at is null while the person is still adding it to their app, and
-- only a confirmed row is asked for at sign-in. last_used_step is the highest time step whose code
-- was accepted, the replay guard.
CREATE TABLE totp_credentials (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       uuid NOT NULL UNIQUE REFERENCES accounts (id) ON DELETE CASCADE,
    organization_id  uuid NOT NULL,
    secret_encrypted text NOT NULL,
    confirmed_at     timestamptz,
    last_used_step   bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now()
);

-- code_hash is the SHA-256 of an 80-bit code, so it is safe to store unsalted and to look up by
-- value; used_at marks a code spent.
CREATE TABLE totp_recovery_codes (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    organization_id uuid NOT NULL,
    code_hash       varchar(64) NOT NULL,
    used_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, code_hash)
);
