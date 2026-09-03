-- TD-FUT-026: platform-tier mirror of known_devices (V20260831090000 + V20260831100000) — new-
-- device login email notification for a PlatformAccount, same opaque-device-token design from day
-- one (unlike known_devices, which started User-Agent-only and needed a follow-up migration,
-- V20260831100000, to fix TD-SEC-033 — same mistake, not repeated here).
--
-- No organization_id column, same rationale as platform_accounts' own migration comment: this
-- table belongs to no Organization at all.
--
-- ON DELETE CASCADE from day one, same "not repeating the social-login tables' own mistake"
-- precedent known_devices' own V20260831090000 already established.
CREATE TABLE platform_known_devices (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    platform_account_id uuid NOT NULL REFERENCES platform_accounts (id) ON DELETE CASCADE,
    user_agent          varchar(512) NOT NULL,
    device_token_hash   varchar(255),
    first_seen_at       timestamptz NOT NULL DEFAULT now(),
    last_seen_at        timestamptz NOT NULL DEFAULT now()
);

-- Same "NULL is distinct from NULL" reasoning as known_devices' own
-- ux_known_devices_account_id_device_token_hash index.
CREATE UNIQUE INDEX ux_platform_known_devices_account_id_device_token_hash
    ON platform_known_devices (platform_account_id, device_token_hash);
