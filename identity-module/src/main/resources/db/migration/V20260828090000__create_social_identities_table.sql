-- ADR-0020, BR-ID-02/BR-ID-09, data-model.md §2: deliberately a separate table from accounts,
-- same "never a nullable column bolted onto the core entity" convention password_credentials
-- already establishes — keeps BR-ID-02 (never zero auth methods) a natural COUNT-across-tables
-- query across both password_credentials and social_identities.
-- organization_id is a plain UUID column, same "no cross-module FK" convention accounts.
-- organization_id already established (see that table's own migration) — not an oversight here.
CREATE TABLE social_identities (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id        uuid NOT NULL REFERENCES accounts (id),
    organization_id   uuid NOT NULL,
    provider          varchar(32) NOT NULL,
    provider_user_id  varchar(255) NOT NULL,
    linked_at         timestamptz NOT NULL DEFAULT now()
);

-- CLAUDE.md §5 / domain-model.md §8: the same real-world Google/GitHub identity can never link to
-- two different accounts WITHIN one Organization, but the same provider identity legitimately
-- links to two independent Accounts in two different Organizations (each Organization owns a
-- fully isolated Account pool) — so organization_id is part of this key, not a plain
-- (provider, provider_user_id) global constraint. A lookup that omitted organization_id here would
-- let a login through Organization B resolve an identity actually owned by Organization A's
-- Account — caught live by code review, never shipped as the original (provider,
-- provider_user_id)-only index below would have allowed.
CREATE UNIQUE INDEX ux_social_identities_org_provider_provider_user_id
    ON social_identities (organization_id, provider, provider_user_id);

-- One link per provider per account — an Account can have at most one linked Google identity and
-- one linked GitHub identity (not "at most one social identity total").
CREATE UNIQUE INDEX ux_social_identities_account_id_provider ON social_identities (account_id, provider);
