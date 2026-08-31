-- New-device login email notification: a persistent, per-account device fingerprint (raw
-- User-Agent string) that outlives any single HttpSession — see KnownDevice's own Javadoc for why
-- the live session store can't answer "have we seen this device before" by itself.
--
-- ON DELETE CASCADE from day one, unlike the social-login tables (V20260828090001 and siblings),
-- which needed a follow-up migration (V20260830100000) to add this — same mistake, not repeated.
CREATE TABLE known_devices (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    user_agent    varchar(512) NOT NULL,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, user_agent)
);
