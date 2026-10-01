-- TD-FUT-034, Clerk "View Profile" activity heatmap parity: a durable, per-login record —
-- previously the only trace of a successful sign-in was a single, overwritten
-- accounts.last_signed_in_at column (SDE-III review, 2026-09-19) plus an SLF4J log line, neither of
-- which can answer "how many times did this Account sign in on each of the last 365 days."
-- ON DELETE CASCADE, same convention known_devices already establishes for this same
-- one-Account-owns-many-rows shape — a hard-deleted Account (BR-DATA-02) takes its own login
-- history with it, no explicit cleanup needed in DeleteAccountService.
CREATE TABLE login_events (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    organization_id uuid NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT now()
);

-- The one real query shape this table exists for: "this Account's own login activity, newest
-- first / within a date range" — same "index for the actual query shape" precedent
-- audit_events's own ix_audit_events_actor already establishes.
CREATE INDEX ix_login_events_account_occurred_at ON login_events (account_id, occurred_at);
