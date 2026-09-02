-- webhook-module (ADR-0007 §1): the dispatcher needs to fan out by tenant (ADR-0010) without
-- having to know every producer's own payload JSON shape — see AbstractEventOutboxEntity's own
-- Javadoc for the full "explicit column, not parsed from payload" reasoning.
ALTER TABLE event_outbox ADD COLUMN organization_id uuid;

-- Backfill any pre-existing rows — this table has been write-only since V20260818153502 (no
-- dispatcher has ever existed to read it), and every identity-module event payload nests its
-- OrganizationId value object the same way: {"organizationId":{"value":"<uuid>"}}.
UPDATE event_outbox
SET organization_id = (payload::jsonb -> 'organizationId' ->> 'value')::uuid
WHERE organization_id IS NULL;

ALTER TABLE event_outbox ALTER COLUMN organization_id SET NOT NULL;

-- Supports the dispatcher's own per-organization fan-out lookup, same reasoning as the two indexes
-- V20260818153502/V20260824090000 already added for the unpublished-rows/retention-sweep queries.
CREATE INDEX ix_event_outbox_organization_id ON event_outbox (organization_id);
