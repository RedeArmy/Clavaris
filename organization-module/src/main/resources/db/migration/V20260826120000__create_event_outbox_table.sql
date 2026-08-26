-- TD-ARCH-007 (SDE-III review, 2026-08-26): organization-module's own outbox table.
--
-- Named organization_event_outbox, NOT event_outbox: confirmed live (application.yml's own
-- comment — Flyway's default classpath:db/migration location matches EVERY module's own
-- db/migration folder, all merged into one shared Postgres schema/history in the real app) that a
-- second table literally named event_outbox would collide outright with identity-module's own
-- (V20260818153502) the moment both modules' migrations run together — this is one shared
-- database, not one per module, unlike each module's own isolated Testcontainers test suite. Same
-- reasoning already forced module-prefixed Java class names here (OrganizationEventOutboxEntity
-- etc., avoiding a Hibernate/Spring bean-name collision with identity-module's own identically
-- shaped classes) — the SQL layer has the exact same constraint.
--
-- Same shape otherwise as identity-module's own table (ADR-0007 §1, data-model.md §2): written in
-- the same transaction as the domain state change it records; published_at IS NULL marks a row
-- still waiting for a dispatcher (webhook-module, 🟡 proposed, not built yet) to fan it out.
CREATE TABLE organization_event_outbox (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(100) NOT NULL,
    aggregate_id   uuid NOT NULL,
    event_type     varchar(100) NOT NULL,
    payload        text NOT NULL,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);

-- Same two indexes as identity-module's own table, same reasoning: the future dispatcher's own
-- poll query (unpublished rows) and OrganizationEventOutboxRetentionJob's own age-based sweep.
CREATE INDEX ix_organization_event_outbox_unpublished
    ON organization_event_outbox (published_at) WHERE published_at IS NULL;
CREATE INDEX ix_organization_event_outbox_occurred_at ON organization_event_outbox (occurred_at);
