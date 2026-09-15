-- Organization-module's own mirror of identity-module's identical V20260915100000 — see that
-- migration's own comment and EventOutboxRetentionSweeper's own Javadoc for the full reasoning.
-- Module-prefixed table name, same collision reason as organization_event_outbox itself
-- (V20260826120000): Flyway's default classpath:db/migration location merges every module's
-- migrations into one shared Postgres schema, so a bare event_outbox_dead_letters here would
-- collide with identity-module's own table of that name.
CREATE TABLE organization_event_outbox_dead_letters (
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

CREATE INDEX ix_organization_event_outbox_dead_letters_organization_id
    ON organization_event_outbox_dead_letters (organization_id);
