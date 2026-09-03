-- SDE-III webhook traceability review: the distributed-tracing id of the inbound request that
-- produced this row, captured at write time from SLF4J's MDC — see AbstractEventOutboxEntity's
-- own Javadoc for why MDC, not an injected Tracer bean. Nullable: every row written before this
-- migration, and any row written off a traced thread, legitimately has none.
ALTER TABLE event_outbox ADD COLUMN trace_id varchar(32);
