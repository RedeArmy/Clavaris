-- SDE-III webhook traceability review: carries the source outbox event's own trace_id (see
-- identity-module's V20260902140000 / organization-module's V20260902140001) through to the
-- outbound Clavaris-Trace-Id header and this delivery's own log lines. Nullable for the same
-- reason as its two source columns.
ALTER TABLE webhook_deliveries ADD COLUMN trace_id varchar(32);
