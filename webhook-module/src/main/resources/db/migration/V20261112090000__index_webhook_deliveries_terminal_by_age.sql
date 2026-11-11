-- WebhookDeliveryRetentionJob removes the terminal deliveries (SUCCEEDED, EXHAUSTED) older than the
-- retention window, a batch at a time (TD-PERF-057). The table gets a row per event per endpoint and
-- is the one that grows fastest, and none of its indexes serves "terminal rows by age": the due
-- index covers PENDING and FAILED, the others lead with the endpoint or the Organization. Without
-- one, every batch would walk the table from the start through the dead rows the previous batch
-- left behind.
--
-- Partial, on exactly the statuses the sweep deletes: it holds only rows that are about to be swept
-- or waiting for their age, and a row stops being in it the moment it is deleted. The sweep writes
-- the same two literals so the planner can prove that its predicate implies this one.
--
-- CONCURRENTLY, because the table can already hold rows: a plain build would block every writer.
CREATE INDEX CONCURRENTLY ix_webhook_deliveries_terminal_created_at
    ON webhook_deliveries (created_at)
    WHERE status IN ('SUCCEEDED', 'EXHAUSTED');
