-- TD-FUT-043: a pending self-service email change. The new address lives here, not on accounts,
-- until its owner consumes the single-use link mailed to it (hash-only, time-limited: BR-ID-04/05).
-- One pending request per account (a newer request replaces it); gone with the account.
CREATE TABLE email_change_requests (
    id          UUID         PRIMARY KEY,
    account_id  UUID         NOT NULL UNIQUE REFERENCES accounts (id) ON DELETE CASCADE,
    new_email   VARCHAR(320) NOT NULL,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ
);
