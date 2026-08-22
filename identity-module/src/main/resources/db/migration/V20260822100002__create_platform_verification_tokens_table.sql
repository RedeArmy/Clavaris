-- Same shape as verification_tokens (BR-ID-04/BR-ID-05, one model for both email verification and
-- password reset, type discriminates), scoped to platform_accounts instead of accounts.
CREATE TABLE platform_verification_tokens (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    platform_account_id   uuid NOT NULL REFERENCES platform_accounts (id),
    type                  varchar(32) NOT NULL,
    token_hash            varchar(64) NOT NULL UNIQUE,
    expires_at            timestamptz NOT NULL,
    consumed_at           timestamptz
);

CREATE INDEX ix_platform_verification_tokens_platform_account_id_type
    ON platform_verification_tokens (platform_account_id, type);
