-- ADR-0007 §1 (🟡 proposed), data-model.md §2: transactional outbox. Written in the same
-- transaction as the domain state change it records; published_at IS NULL marks a row still
-- waiting for a dispatcher to fan it out. No dispatcher exists yet (webhook-module isn't built) —
-- this table is write-only for now, exactly as any future outbox consumer would find it once it
-- comes online; nothing about this schema assumes a specific consumer exists yet.
CREATE TABLE event_outbox (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(100) NOT NULL,
    aggregate_id   uuid NOT NULL,
    event_type     varchar(100) NOT NULL,
    payload        text NOT NULL,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);

-- data-model.md §3: dispatcher poll query — partial index keeps it small regardless of total
-- outbox history, since published rows never need to be scanned again.
CREATE INDEX ix_event_outbox_unpublished ON event_outbox (published_at) WHERE published_at IS NULL;
