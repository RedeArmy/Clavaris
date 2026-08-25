-- TD-SEC-026: per-client, secure-by-default consent policy. requireAuthorizationConsent was never
-- set anywhere on the RegisteredClient this project hands Spring Authorization Server, so its own
-- default (false) applied unconditionally — every client, for every Organization, was implicitly
-- pre-authorized and the end user was never shown what scopes it was requesting. Defaults true for
-- every already-registered client (same "safe default on an ALTER, opt out explicitly per row"
-- convention as TD-SEC-018's own platform_clients.active column) — an operator who wants a trusted
-- first-party client (JobSeeker today) to skip the consent screen sets this false explicitly via a
-- deliberate, auditable action, not by inheriting silence.
ALTER TABLE oauth_clients ADD COLUMN require_consent boolean NOT NULL DEFAULT true;
