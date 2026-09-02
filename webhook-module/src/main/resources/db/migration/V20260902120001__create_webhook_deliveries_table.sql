-- ADR-0007 §2: one fan-out target for one outbox event (WebhookDelivery's own Javadoc). payload is
-- a snapshot captured at fan-out time, not a live read of the source outbox row.
CREATE TABLE webhook_deliveries (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id           uuid NOT NULL REFERENCES webhook_endpoints (id) ON DELETE CASCADE,
    organization_id       uuid NOT NULL,
    outbox_event_id       uuid NOT NULL,
    aggregate_type        varchar(100),
    aggregate_id          uuid,
    event_type            varchar(100),
    payload               text,
    status                varchar(20) NOT NULL,
    attempt_count         integer NOT NULL DEFAULT 0,
    next_attempt_at       timestamptz,
    last_attempt_at       timestamptz,
    last_response_status  integer,
    last_error            varchar(500),
    created_at            timestamptz NOT NULL DEFAULT now(),

    -- One row per (endpoint, outbox event) — DispatchOutboxEventsService's own fan-out must never
    -- double-schedule the same event to the same endpoint even if a dispatch tick somehow observed
    -- the same still-unpublished outbox row twice.
    CONSTRAINT ux_webhook_deliveries_endpoint_outbox_event UNIQUE (endpoint_id, outbox_event_id)
);

-- DeliverPendingWebhooksService's own claim query: rows due right now, oldest first.
CREATE INDEX ix_webhook_deliveries_due
    ON webhook_deliveries (next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');

-- ListWebhookDeliveriesForEndpointService's own per-endpoint history lookup.
CREATE INDEX ix_webhook_deliveries_endpoint_id_created_at
    ON webhook_deliveries (endpoint_id, created_at DESC);
