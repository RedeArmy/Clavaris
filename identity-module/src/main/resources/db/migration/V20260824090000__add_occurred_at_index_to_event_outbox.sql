-- TD-TEST-002: supports EventOutboxRetentionJob's age-based sweep (occurred_at < cutoff), the same
-- way ix_event_outbox_unpublished already supports the dispatcher's own poll query. Without this,
-- the retention sweep would be a full table scan the moment real registrations give this table more
-- than a handful of rows.
CREATE INDEX ix_event_outbox_occurred_at ON event_outbox (occurred_at);
