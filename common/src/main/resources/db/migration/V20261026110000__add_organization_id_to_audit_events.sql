-- The tenant key audit_events never had (TD-ARCH-038). The Organization's audit log was assembled by
-- listing every id the Organization owns (workspaces, every membership, OAuth clients, webhook
-- endpoints) and querying (target_type, target_id) IN (...), so its cost grew with the number of
-- memberships, it could not include events about the Organization's accounts at all, and it had no
-- way to page. With the owning Organization stored on the row the page is one indexed range scan
-- ordered by time, whatever the Organization owns.
--
-- Nullable on purpose: platform-level events (platform accounts, platform clients) belong to no
-- Organization, and so do events whose target no longer existed when they were recorded.
-- Backfilling the rows already written is a separate migration in the app module
-- (V20261026110100), because it has to read tables that belong to other modules.
ALTER TABLE audit_events ADD COLUMN organization_id uuid;

CREATE INDEX ix_audit_events_organization_occurred
    ON audit_events (organization_id, occurred_at DESC, id DESC)
    WHERE organization_id IS NOT NULL;
