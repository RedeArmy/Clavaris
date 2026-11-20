-- TD-ARCH-063: the operator tier's counterpart of pending_session_closures (TD-ARCH-057). Closing a
-- platform account's dashboard sessions needs Redis, and suspending an operator or resetting an
-- operator's password must not fail because Redis is down. When the close cannot be done the
-- account is recorded here, in the same transaction as the change, and a job closes the sessions
-- once Redis answers. One row per account; no foreign key, so the record never blocks a deletion.
CREATE TABLE pending_platform_session_closures (
    platform_account_id UUID        PRIMARY KEY,
    recorded_at         TIMESTAMPTZ NOT NULL
);
