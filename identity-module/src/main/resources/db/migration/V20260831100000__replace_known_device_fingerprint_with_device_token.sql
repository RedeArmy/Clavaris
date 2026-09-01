-- TD-SEC-033 (SDE-III review, 2026-08-31): known_devices' fingerprint was the raw, unauthenticated
-- User-Agent header — an attacker who has stolen a live session (or is probing credential-stuffing
-- hits) could spoof the victim's own real User-Agent string to suppress the "new device"
-- notification outright, since the string is fully client-controlled and often guessable/public.
--
-- Replaced with an opaque, high-entropy, HttpOnly device cookie as the actual security-relevant
-- match key — never the raw cookie value, only its SHA-256 hash is stored here, same
-- hash-not-plaintext principle every other secret this system issues already follows
-- (RefreshTokenSecret/VerificationToken). An attacker's own browser can never present a value that
-- hashes to a row it never received, regardless of what User-Agent it sends.
--
-- user_agent stays (display/audit only, no longer the match key) — the old
-- UNIQUE(account_id, user_agent) constraint is dropped because it's no longer a valid invariant
-- under the new design: clearing cookies and logging back in from the "same" browser/OS is now a
-- legitimate second row (a new device token, an old User-Agent string), not a constraint violation.
-- Constraint name follows Postgres's own default <table>_<col1>_<col2>_key convention, same
-- documented assumption V20260830100000 already relies on for this table family.
ALTER TABLE known_devices DROP CONSTRAINT known_devices_account_id_user_agent_key;

ALTER TABLE known_devices ADD COLUMN device_token_hash varchar(255);

-- No WHERE clause needed: Postgres's own UNIQUE semantics already treat NULL as distinct from
-- any other NULL, so multiple NULL device_token_hash rows (none expected in practice — every
-- detection going forward always mints a token) would never spuriously conflict with each other.
CREATE UNIQUE INDEX ux_known_devices_account_id_device_token_hash
    ON known_devices (account_id, device_token_hash);
