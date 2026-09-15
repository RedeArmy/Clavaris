-- TD-ARCH-007 follow-up (SDE-III review, 2026-09-15): closes "the retention sweep silently
-- destroys undelivered events" — EventOutboxRetentionSweeper used to WARN with a count of
-- still-unpublished rows about to be purged, then delete them anyway with no way to ever recover
-- or even inspect which events were lost. The sweep now archives an unpublished row's full content
-- here before deleting it from event_outbox, so an operator paged by that WARN (or auditing after
-- the fact) has something to actually investigate or manually replay, not just a number.
--
-- Same columns as event_outbox itself (AbstractEventOutboxEntity) minus published_at (a
-- dead-lettered row is one that was never published, by construction) plus swept_at. No dispatcher
-- reads this table — it exists purely for operator recovery/audit, so id is copied verbatim from
-- the source row rather than defaulted, and there is no FK back to event_outbox: the whole point is
-- to survive after the source row is gone.
CREATE TABLE event_outbox_dead_letters (
    id               uuid PRIMARY KEY,
    organization_id  uuid NOT NULL,
    aggregate_type   varchar(100) NOT NULL,
    aggregate_id     uuid NOT NULL,
    event_type       varchar(100) NOT NULL,
    payload          text NOT NULL,
    trace_id         varchar(32),
    occurred_at      timestamptz NOT NULL,
    swept_at         timestamptz NOT NULL DEFAULT now()
);

-- Mirrors event_outbox's own organization_id index — an operator investigating one tenant's lost
-- events is the expected access pattern, same reasoning as the live table's own index.
CREATE INDEX ix_event_outbox_dead_letters_organization_id
    ON event_outbox_dead_letters (organization_id);
