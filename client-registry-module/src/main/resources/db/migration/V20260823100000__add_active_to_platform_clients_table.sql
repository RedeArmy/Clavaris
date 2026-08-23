-- TD-SEC-018: a PlatformClient now has a real, code-driven way to be revoked
-- (DeactivatePlatformClientService) instead of only raw SQL against production. Defaults true so
-- every already-bootstrapped client stays authenticatable across this migration, unchanged.
ALTER TABLE platform_clients ADD COLUMN active boolean NOT NULL DEFAULT true;
