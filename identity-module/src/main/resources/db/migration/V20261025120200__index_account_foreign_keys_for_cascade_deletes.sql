-- Deleting an account (and, for a whole Organization, every account in it) cascades to sessions and
-- refresh_tokens (V20260826100000). Postgres enforces each cascade with
--   DELETE FROM child WHERE account_id = $1
-- but the only indexes on account_id were PARTIAL (... WHERE revoked_at IS NULL). A partial index
-- cannot serve a lookup that does not repeat its predicate, so every deleted account cost a full scan
-- of both tables. Measured with 1,000,000 session rows: 96.7 ms to delete one account, against 0.27
-- ms with a plain index on the column. A tenant of 100,000 accounts would spend about 2.7 hours per
-- table on the scans alone, inside the one transaction DeleteOrganizationService runs.
--
-- The plain index also answers the "active tokens/sessions of this account" queries the partial ones
-- were written for (it just filters revoked rows out afterwards, a handful per account), so the
-- partial ones are replaced rather than kept as a redundant second index to maintain on every
-- session and refresh-token insert.
--
-- refresh_tokens.session_id had no index at all, and sessions cascades into refresh_tokens through
-- it, so deleting a session scanned the whole refresh_tokens table too.
--
-- Plain CREATE INDEX: Flyway is transactional here (TD-FUT-039) and the tables are small today.
DROP INDEX ix_sessions_account_id_active;
CREATE INDEX ix_sessions_account_id ON sessions (account_id);

DROP INDEX ix_refresh_tokens_account_id_active;
CREATE INDEX ix_refresh_tokens_account_id ON refresh_tokens (account_id);
CREATE INDEX ix_refresh_tokens_session_id ON refresh_tokens (session_id);
