CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_hot_events_organization_occurred
    ON hot_events (organization_id, occurred_at);
