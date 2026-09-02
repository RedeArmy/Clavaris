-- webhook-module (ADR-0007 §1): same "explicit column, not parsed from payload" reasoning as
-- identity-module's own identical migration against event_outbox — see
-- AbstractEventOutboxEntity's own Javadoc.
ALTER TABLE organization_event_outbox ADD COLUMN organization_id uuid;

-- Backfill pass 1: Organization/Workspace aggregate rows (OrganizationDeletedEvent,
-- WorkspaceCreatedEvent) carry a bare organizationId field directly in their own payload.
UPDATE organization_event_outbox
SET organization_id = (payload::jsonb ->> 'organizationId')::uuid
WHERE organization_id IS NULL
  AND payload::jsonb ? 'organizationId';

-- Backfill pass 2: WorkspaceMembership aggregate rows (WorkspaceMemberAdded/RoleChanged/Removed)
-- never carried organizationId in their own payload, only workspaceId — resolve it via the
-- workspace they belong to.
UPDATE organization_event_outbox oeo
SET organization_id = w.organization_id
FROM workspaces w
WHERE oeo.organization_id IS NULL
  AND (oeo.payload::jsonb ->> 'workspaceId')::uuid = w.id;

-- Any row still unresolved (its own workspace has since been deleted — theoretically possible,
-- practically only in pre-launch dev data) is unroutable garbage: this table has been write-only
-- since V20260826120000, so no dispatcher could ever have delivered it anyway. Delete rather than
-- block this migration on data that was never going to be deliverable.
DELETE FROM organization_event_outbox WHERE organization_id IS NULL;

ALTER TABLE organization_event_outbox ALTER COLUMN organization_id SET NOT NULL;

CREATE INDEX ix_organization_event_outbox_organization_id ON organization_event_outbox (organization_id);
