-- SDE-III review, 2026-09-15: closes a real lost-update race — deactivate and rotate-secret could
-- run concurrently against the same row with no guard, so whichever save() committed last silently
-- overwrote the other's change (e.g. rotate-secret re-activating a client deactivate had just
-- revoked). DEFAULT 0 backfills every existing row for free; every future UPDATE goes through
-- Hibernate's @Version-driven `SET version = version + 1 WHERE id = ? AND version = ?`.
ALTER TABLE oauth_clients ADD COLUMN version integer NOT NULL DEFAULT 0;
