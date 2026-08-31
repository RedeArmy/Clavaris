-- ADR-0020 Decision 1, BR-ID-09: the confirmation-step table the account-linking decision
-- requires — a social login whose verified email matches an existing account created by a
-- different method never links automatically; this row is the staging area for that link until
-- the account holder confirms it through the email of record. Never itself a valid authentication
-- method. Same hash-only, single-use, time-limited shape as verification_tokens.
CREATE TABLE pending_social_links (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id                uuid NOT NULL REFERENCES accounts (id),
    provider                  varchar(32) NOT NULL,
    provider_user_id          varchar(255) NOT NULL,
    confirmation_token_hash   varchar(64) NOT NULL UNIQUE,
    expires_at                timestamptz NOT NULL,
    consumed_at               timestamptz
);

-- Same access-pattern precedent as verification_tokens' own ix_verification_tokens_account_id_type
-- — "is there already an active pending link for this account/provider" (re-triggering a social
-- login that already has one pending shouldn't silently create a second).
CREATE INDEX ix_pending_social_links_account_id_provider ON pending_social_links (account_id, provider);
