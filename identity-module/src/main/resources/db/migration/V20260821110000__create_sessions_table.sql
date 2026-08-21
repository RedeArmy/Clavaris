-- BR-ID-03, data-model.md §2: one continuous login — the anchor a chain of rotated refresh_tokens
-- attaches to (domain-model.md §8's own resolution: kept as a separate aggregate from
-- refresh_tokens specifically because reuse-detection needs to reason about the whole chain, not
-- just whichever token is currently active). A real FK to accounts is safe here, unlike
-- signing_keys' own organization_id column — Account lives in this same module's migration
-- history (V20260818153500), guaranteed to run first.
CREATE TABLE sessions (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    uuid NOT NULL REFERENCES accounts (id),
    scopes        text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz
);

-- BR-ID-03's reuse-detection cascade revokes every active session for an account in one query.
CREATE INDEX ix_sessions_account_id_active ON sessions (account_id) WHERE revoked_at IS NULL;
