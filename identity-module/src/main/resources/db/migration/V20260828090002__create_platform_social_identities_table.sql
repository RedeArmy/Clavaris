-- Same shape as social_identities (ADR-0020, BR-ID-09/BR-ID-10), scoped to platform_accounts
-- instead of accounts — social login is additive, permanently coexisting with
-- platform_password_credentials for a given PlatformAccount (ADR-0020 Decision 2), never a
-- replacement.
CREATE TABLE platform_social_identities (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    platform_account_id   uuid NOT NULL REFERENCES platform_accounts (id),
    provider              varchar(32) NOT NULL,
    provider_user_id      varchar(255) NOT NULL,
    linked_at             timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_platform_social_identities_provider_provider_user_id
    ON platform_social_identities (provider, provider_user_id);

CREATE UNIQUE INDEX ux_platform_social_identities_platform_account_id_provider
    ON platform_social_identities (platform_account_id, provider);
