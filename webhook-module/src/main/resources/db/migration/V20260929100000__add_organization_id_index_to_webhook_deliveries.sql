-- TD-PERF-025: the 2026-09-25 org-wide Logs/Activity feature added four query methods
-- (SpringDataWebhookDeliveryJpaRepository#findFirstPageByOrganizationId/
-- findPageByOrganizationIdAfter/findPageByOrganizationIdBefore/
-- findAllByOrganizationIdAndLastAttemptAtGreaterThanEqual) filtering by organization_id alone —
-- neither existing index (ix_webhook_deliveries_due, ix_webhook_deliveries_endpoint_id_created_at)
-- leads with organization_id, so both the keyset-paginated Logs page and the hourly-bucket
-- activity aggregation full-scan this table across every Organization, not just the requesting
-- one. Unlike webhook_endpoints (capped at 25/Organization, BR-WEBHOOK-08), webhook_deliveries has
-- no per-organization cap and is the target of every dispatch fan-out plus every retry — this
-- module's fastest-growing table.
CREATE INDEX ix_webhook_deliveries_organization_id_created_at
    ON webhook_deliveries (organization_id, created_at DESC);
