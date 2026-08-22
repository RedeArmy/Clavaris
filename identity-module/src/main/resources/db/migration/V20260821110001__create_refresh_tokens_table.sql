-- BR-ID-03, data-model.md §2: token_hash, never the bearer value itself (same hash-not-plaintext
-- principle as password_credentials and verification_tokens) — deliberately NOT the same table as
-- Spring Authorization Server's own oauth2_authorization (TD-SEC-003/TD-SEC-019): that table stores
-- every token's raw value because JdbcOAuth2AuthorizationService's own findByToken lookup requires
-- it, with no supported way to hash on write. Refresh tokens never go through that lookup at all —
-- RotateRefreshTokenService validates entirely against this table instead, so they keep the
-- hash-only guarantee every other bearer secret this project designed its own schema for already
-- has.
CREATE TABLE refresh_tokens (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       uuid NOT NULL REFERENCES sessions (id),
    account_id       uuid NOT NULL REFERENCES accounts (id),
    token_hash       varchar(64) NOT NULL, -- hex-encoded SHA-256, always exactly 64 characters
    rotated_from_id  uuid REFERENCES refresh_tokens (id),
    issued_at        timestamptz NOT NULL DEFAULT now(),
    expires_at       timestamptz NOT NULL,
    revoked_at       timestamptz
);

-- Token-presentation lookup at rotation time — the single most latency-sensitive query this table
-- serves.
CREATE UNIQUE INDEX ux_refresh_tokens_token_hash ON refresh_tokens (token_hash);

-- BR-ID-03's reuse-detection cascade revokes every active refresh token for an account in one
-- query.
CREATE INDEX ix_refresh_tokens_account_id_active ON refresh_tokens (account_id) WHERE revoked_at IS NULL;

-- Rotation-chain audit walk (RefreshToken's own Javadoc: rotated_from_id is the "why/when" trail,
-- not the reuse check's own source of truth, but still worth indexing for investigation queries).
CREATE INDEX ix_refresh_tokens_rotated_from_id ON refresh_tokens (rotated_from_id) WHERE rotated_from_id IS NOT NULL;
