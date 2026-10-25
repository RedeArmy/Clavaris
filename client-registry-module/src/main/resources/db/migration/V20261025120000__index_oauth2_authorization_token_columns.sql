-- Spring Authorization Server looks an authorization up by the token a caller presents:
-- WHERE authorization_code_value = ? at the code exchange, WHERE state = ? at consent, and for
-- revocation/introspection (which do not know the token's type) one query ORing all seven token
-- columns. The upstream schema creates no index on any of them, so every one of those lookups was a
-- sequential scan of the whole table, which grows by a row per sign-in and is only trimmed nightly.
-- Measured on 200,000 rows: 151 ms for the code lookup (three parallel workers) and 57 ms for the
-- OR query, against 0.04 ms and 3 ms with these indexes.
--
-- Partial (WHERE col IS NOT NULL): most rows leave most of these columns empty (refresh_token_value,
-- user_code_value and device_code_value are never written at all), so the unused ones cost almost
-- nothing; an equality search implies NOT NULL, so the planner still uses them. All seven are needed
-- together: the OR query can only avoid the sequential scan if every column in it has an index.
--
-- Plain CREATE INDEX is deliberate: Flyway runs migrations in a transaction here (TD-FUT-039), and
-- the table is still small, which is the cheapest moment to add them.
CREATE INDEX ix_oauth2_authorization_state
    ON oauth2_authorization (state) WHERE state IS NOT NULL;
CREATE INDEX ix_oauth2_authorization_authorization_code_value
    ON oauth2_authorization (authorization_code_value) WHERE authorization_code_value IS NOT NULL;
CREATE INDEX ix_oauth2_authorization_access_token_value
    ON oauth2_authorization (access_token_value) WHERE access_token_value IS NOT NULL;
CREATE INDEX ix_oauth2_authorization_oidc_id_token_value
    ON oauth2_authorization (oidc_id_token_value) WHERE oidc_id_token_value IS NOT NULL;
CREATE INDEX ix_oauth2_authorization_refresh_token_value
    ON oauth2_authorization (refresh_token_value) WHERE refresh_token_value IS NOT NULL;
CREATE INDEX ix_oauth2_authorization_user_code_value
    ON oauth2_authorization (user_code_value) WHERE user_code_value IS NOT NULL;
CREATE INDEX ix_oauth2_authorization_device_code_value
    ON oauth2_authorization (device_code_value) WHERE device_code_value IS NOT NULL;
