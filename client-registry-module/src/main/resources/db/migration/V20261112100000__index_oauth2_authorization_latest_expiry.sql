-- OAuth2AuthorizationRetentionJob removes the grants whose every token has expired, in batches
-- (TD-PERF-060). "Every token has expired" is GREATEST over the six *_expires_at columns, which no
-- existing index covers, so each batch would read the table from the start through the dead rows
-- the previous batch left behind, and the table keeps a row with token values for every grant.
--
-- An expression index on exactly that GREATEST lets the sweep walk the oldest grants first and stop
-- after one batch. The sweep writes the expression in the same column order so the planner matches
-- it. Rows whose columns are all NULL have a NULL there and are never swept, as before.
--
-- CONCURRENTLY, because the table already holds rows and a plain build would block every writer,
-- that is every token issuance.
CREATE INDEX CONCURRENTLY ix_oauth2_authorization_latest_expiry
    ON oauth2_authorization ((GREATEST(
        authorization_code_expires_at,
        access_token_expires_at,
        oidc_id_token_expires_at,
        refresh_token_expires_at,
        user_code_expires_at,
        device_code_expires_at
    )));
