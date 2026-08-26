-- BR-DATA-02/03: the admin account-deletion API hard-deletes the Account row, never anonymizes it
-- — every table whose only reason to exist is "this Account's own data" must go with it,
-- structurally, not by an application-layer delete-list a future new table could silently be left
-- off of. DeleteAccountService still explicitly revokes the account's SAS-managed access/ID tokens
-- first (AccountTokenRevoker, oauth2_authorization has no FK relationship to accounts at all, so
-- no cascade could reach it) — this migration only closes the four tables that DO have a real FK.
--
-- Constraint names confirmed live against a real, freshly-migrated Postgres instance (default
-- Postgres <table>_<column>_fkey naming — none of the original migrations named them explicitly),
-- not assumed from the CREATE TABLE statements' own inline REFERENCES clauses.
ALTER TABLE password_credentials DROP CONSTRAINT password_credentials_account_id_fkey;
ALTER TABLE password_credentials
    ADD CONSTRAINT password_credentials_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE;

ALTER TABLE sessions DROP CONSTRAINT sessions_account_id_fkey;
ALTER TABLE sessions
    ADD CONSTRAINT sessions_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE;

-- refresh_tokens has two FKs to close: account_id (direct) and session_id (indirect — a session
-- being cascade-deleted above must also cascade-delete every refresh_tokens row that references
-- it, not just the rows referencing the account directly). refresh_tokens_rotated_from_id_fkey
-- (self-referencing, the rotation-chain audit trail) needs no change: every row in a rotation
-- chain shares the same account_id, so the account_id cascade below already removes the whole
-- chain in one statement regardless of rotated_from_id linkage.
ALTER TABLE refresh_tokens DROP CONSTRAINT refresh_tokens_account_id_fkey;
ALTER TABLE refresh_tokens
    ADD CONSTRAINT refresh_tokens_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE;

ALTER TABLE refresh_tokens DROP CONSTRAINT refresh_tokens_session_id_fkey;
ALTER TABLE refresh_tokens
    ADD CONSTRAINT refresh_tokens_session_id_fkey
    FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE;

ALTER TABLE verification_tokens DROP CONSTRAINT verification_tokens_account_id_fkey;
ALTER TABLE verification_tokens
    ADD CONSTRAINT verification_tokens_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE;
