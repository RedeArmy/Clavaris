-- audit_events is append-only and, unlike every other event table in this schema, had no retention
-- at all (TD-FUT-047). Range-partitioning it by month while it is small makes retention a DROP of a
-- whole partition (instant, no bloat, no long DELETE) instead of a delete that scans tens of
-- millions of rows later, and bounds what any one query has to look at.
--
-- A partitioned table's primary key must contain the partition key, so it becomes (id, occurred_at).
-- Nothing refers to audit_events by foreign key, and the entity reads and writes by id, which stays
-- unique in practice (gen_random_uuid()).
--
-- Months are UTC, written out explicitly: a partition bound given as a bare date would be read in
-- whatever time zone the session happens to have, and the maintenance job that creates later
-- partitions must agree with this migration about where a month starts. The DEFAULT partition is a
-- safety net, not a plan: an event must never be lost because nobody created next month's
-- partition, but anything that lands there means the maintenance job is not keeping up, and it
-- logs a warning when it finds rows in it. The job creates partitions ahead (three months by
-- default), so it would have to be down for that long before the default is used at all.
--
-- The existing rows are copied into the new table and the old one dropped in this one transaction.
-- That is fine at today's size; on a table with tens of millions of rows run it in a maintenance
-- window or copy in batches first (deployment-runbook section 6f).
ALTER TABLE audit_events RENAME TO audit_events_unpartitioned;
ALTER INDEX audit_events_pkey RENAME TO audit_events_unpartitioned_pkey;
ALTER INDEX ix_audit_events_actor RENAME TO ix_audit_events_unpartitioned_actor;
ALTER INDEX ix_audit_events_target RENAME TO ix_audit_events_unpartitioned_target;
ALTER INDEX ix_audit_events_organization_occurred
    RENAME TO ix_audit_events_unpartitioned_organization_occurred;

CREATE TABLE audit_events (
    id              uuid         NOT NULL DEFAULT gen_random_uuid(),
    actor_type      varchar(32)  NOT NULL,
    actor_id        varchar(255) NOT NULL,
    action          varchar(128) NOT NULL,
    target_type     varchar(64)  NOT NULL,
    target_id       varchar(255),
    detail          text,
    occurred_at     timestamptz  NOT NULL DEFAULT now(),
    organization_id uuid,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Created on the parent, so every partition, present and future, gets the same indexes.
CREATE INDEX ix_audit_events_actor ON audit_events (actor_type, actor_id, occurred_at);
CREATE INDEX ix_audit_events_target ON audit_events (target_type, target_id, occurred_at);
CREATE INDEX ix_audit_events_organization_occurred
    ON audit_events (organization_id, occurred_at DESC, id DESC)
    WHERE organization_id IS NOT NULL;

CREATE TABLE audit_events_default PARTITION OF audit_events DEFAULT;

DO $$
DECLARE
    month_start timestamp;
    last_month  timestamp;
BEGIN
    SELECT date_trunc('month', COALESCE(min(occurred_at), now()) AT TIME ZONE 'UTC')
      INTO month_start FROM audit_events_unpartitioned;
    last_month := date_trunc('month', now() AT TIME ZONE 'UTC') + interval '3 months';
    WHILE month_start <= last_month LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF audit_events FOR VALUES FROM (%L) TO (%L)',
            'audit_events_' || to_char(month_start, 'YYYY_MM'),
            to_char(month_start, 'YYYY-MM-DD') || ' 00:00:00+00',
            to_char(month_start + interval '1 month', 'YYYY-MM-DD') || ' 00:00:00+00');
        month_start := month_start + interval '1 month';
    END LOOP;
END
$$;

INSERT INTO audit_events
    (id, actor_type, actor_id, action, target_type, target_id, detail, occurred_at, organization_id)
SELECT id, actor_type, actor_id, action, target_type, target_id, detail, occurred_at, organization_id
FROM audit_events_unpartitioned;

DROP TABLE audit_events_unpartitioned;
