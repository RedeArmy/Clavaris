-- TD-FUT-034, Clerk "View Profile" passkeys parity — one row per WebAuthn/passkey credential an
-- Account has registered. ON DELETE CASCADE from day one, same known_devices lesson that
-- migration's own comment already documents (a follow-up migration was needed there because the
-- first pass omitted it — not repeated here).
-- credential_id/public_key_cose are stored plain, never hashed: both are public by design (the
-- public half of a key pair), unlike known_devices.device_token_hash or
-- verification_tokens.token_hash, which guard genuinely secret values.
CREATE TABLE webauthn_credentials (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    organization_id uuid NOT NULL,
    credential_id   bytea NOT NULL UNIQUE,
    public_key_cose bytea NOT NULL,
    signature_count bigint NOT NULL DEFAULT 0,
    transports      varchar(255),
    nickname        varchar(100),
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_used_at    timestamptz
);
CREATE INDEX ix_webauthn_credentials_account_id ON webauthn_credentials (account_id);
