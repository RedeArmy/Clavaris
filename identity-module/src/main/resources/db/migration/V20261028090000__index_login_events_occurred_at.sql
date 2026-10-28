-- TD-PERF-049: the retention sweep deletes login_events by age alone. The existing index leads with
-- account_id, so it cannot serve a delete that spans every account.
CREATE INDEX ix_login_events_occurred_at ON login_events (occurred_at);
