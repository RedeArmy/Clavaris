-- TD-ARCH-057: closing an account's browser sessions needs Redis, and a suspension or a deletion
-- must not fail because Redis is down. When the close cannot be done, the account is recorded here,
-- in the same transaction as the state change, and a job closes the sessions once Redis answers.
-- One row per account (a second failure for the same account adds nothing); no foreign key, because
-- a deleted account still has sessions to close.
CREATE TABLE pending_session_closures (
    account_id  UUID        PRIMARY KEY,
    recorded_at TIMESTAMPTZ NOT NULL
);
