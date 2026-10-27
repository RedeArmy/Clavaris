-- The Users tab search (ADR-0029) matches LOWER(first_name), LOWER(last_name), LOWER(username) and
-- LOWER(email) against '%term%'. A leading wildcard cannot use a B-tree, so every search scanned
-- and filtered the whole tenant: measured on 400,000 accounts, 95 to 105 ms for any term, and
-- 412 ms on 500,000 with the keyset index in place (TD-PERF-041).
--
-- pg_trgm GIN indexes on exactly those four expressions let Postgres BitmapOr them: 0.3 ms for a
-- rare term and 24 ms for an email fragment on a cold cache. The price is index size (about 157 MB
-- for 550,000 rows, measured) and write amplification on every account insert and on updates of
-- these four columns. pg_trgm is a trusted extension since Postgres 13, so the database owner can
-- create it; a managed database that restricts extensions must allow it before this migration.
--
-- Plain CREATE INDEX, as in the sibling index migrations: Flyway is transactional here and a
-- CONCURRENT build cannot run in a transaction. It holds a lock that blocks writes to accounts for
-- the build, short while the table is small; build by hand with CONCURRENTLY first on a large one.
-- A term shorter than three characters yields no trigrams, so it still scans, as before.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX ix_accounts_first_name_trgm ON accounts USING gin (LOWER(first_name) gin_trgm_ops);
CREATE INDEX ix_accounts_last_name_trgm ON accounts USING gin (LOWER(last_name) gin_trgm_ops);
CREATE INDEX ix_accounts_username_trgm ON accounts USING gin (LOWER(username) gin_trgm_ops);
CREATE INDEX ix_accounts_email_trgm ON accounts USING gin (LOWER(email) gin_trgm_ops);
