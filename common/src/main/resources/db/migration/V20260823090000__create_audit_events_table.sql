-- TD-SEC-007: durable, queryable audit trail for admin/operator actions — distinct from the
-- event_outbox table (webhook delivery, ADR-0007) and from plain structured log lines
-- (TD-SEC-014/016/017). No FK to organizations/platform_accounts/platform_clients on purpose:
-- this table lives in the shared-kernel `common` module, which — same dependency direction as the
-- Java package structure itself — must never depend on any single business module's own schema.
-- actor_id/target_id are therefore plain text, not typed uuid columns with a foreign key.
CREATE TABLE audit_events (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_type   varchar(32) NOT NULL,
    actor_id     varchar(255) NOT NULL,
    action       varchar(128) NOT NULL,
    target_type  varchar(64) NOT NULL,
    target_id    varchar(255),
    detail       text,
    occurred_at  timestamptz NOT NULL DEFAULT now()
);

-- The two real query shapes an audit trail actually needs: "everything this actor did" (an
-- incident review starting from a compromised credential) and "everything that happened to this
-- target" (an incident review starting from a specific Organization/key/client).
CREATE INDEX ix_audit_events_actor ON audit_events (actor_type, actor_id, occurred_at);
CREATE INDEX ix_audit_events_target ON audit_events (target_type, target_id, occurred_at);
