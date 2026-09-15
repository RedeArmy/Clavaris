-- SDE-III review, 2026-09-15: same lost-update race, same fix — see
-- V20260915090000__add_version_to_oauth_clients_table.sql's own comment for the full rationale.
ALTER TABLE organization_clients ADD COLUMN version integer NOT NULL DEFAULT 0;
