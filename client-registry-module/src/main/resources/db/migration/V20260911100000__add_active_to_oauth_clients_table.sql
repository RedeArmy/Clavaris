-- SDE-III review, 2026-09-11: OAuthClient itself had no way to be deactivated or have its secret
-- rotated until now — a real, pre-existing gap relative to its sibling credential types
-- (organization_clients.active, V20260905100000; platform_clients.active, V20260823100000). Same
-- "safe default on an ALTER, opt out explicitly per row" convention as both of those: every
-- already-registered OAuthClient stays reachable exactly as before this migration, an operator (or
-- the Organization's own PlatformAccount, via the dashboard) deactivates one explicitly, per row.
ALTER TABLE oauth_clients ADD COLUMN active boolean NOT NULL DEFAULT true;
