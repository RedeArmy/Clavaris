-- SDE-III webhook traceability review — organization-module's own mirror of identity-module's
-- identical V20260902140000; see that migration's own comment and AbstractEventOutboxEntity's
-- Javadoc for the full reasoning.
ALTER TABLE organization_event_outbox ADD COLUMN trace_id varchar(32);
