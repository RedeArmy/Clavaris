-- Same shape as pending_social_links (ADR-0020 Decision 1, BR-ID-09), scoped to
-- platform_accounts instead of accounts.
CREATE TABLE pending_platform_social_links (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    platform_account_id       uuid NOT NULL REFERENCES platform_accounts (id),
    provider                  varchar(32) NOT NULL,
    provider_user_id          varchar(255) NOT NULL,
    confirmation_token_hash   varchar(64) NOT NULL UNIQUE,
    expires_at                timestamptz NOT NULL,
    consumed_at               timestamptz
);

CREATE INDEX ix_pending_platform_social_links_platform_account_id_provider
    ON pending_platform_social_links (platform_account_id, provider);
