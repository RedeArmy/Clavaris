-- BR-ID-04/BR-ID-05, data-model.md §2: one table serving both email verification and password
-- reset (type discriminates) — hash-only, same convention as refresh_tokens/password_credentials.
-- A real FK to accounts is safe here for the same reason sessions' own is (V20260818153500 runs
-- first in this module's own migration history).
CREATE TABLE verification_tokens (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id   uuid NOT NULL REFERENCES accounts (id),
    type         varchar(32) NOT NULL,
    token_hash   varchar(64) NOT NULL UNIQUE,
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz
);

-- token_hash already has a unique index via the column constraint above — this second index
-- supports the "is there already an active token for this account/type" access pattern a future
-- resend-throttling feature would need (not built yet, TD-SEC-001's own rate-limiting gap covers
-- abuse of this endpoint in the meantime).
CREATE INDEX ix_verification_tokens_account_id_type ON verification_tokens (account_id, type);
