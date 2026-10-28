-- The nightly retention sweep (ExpiredIdentityRecordsRetentionJob) deletes refresh tokens by
-- expires_at and sessions by last_seen_at. Without these two indexes each sweep is a sequential scan
-- of the two tables that grow with every sign-in and every refresh. The other swept tables hold
-- short-lived tokens, stay small once swept, and are left to a sequential scan.
CREATE INDEX ix_refresh_tokens_expires_at ON refresh_tokens (expires_at);

CREATE INDEX ix_sessions_last_seen_at ON sessions (last_seen_at);
