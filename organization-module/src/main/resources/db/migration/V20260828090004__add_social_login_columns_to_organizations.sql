-- ADR-0020 Decision 3, BR-ID-12: whether this Organization's own Account population may sign in
-- via social login, and with which providers. Defaults closed (false, empty) — an Organization
-- opts in, never inherited by omission, same "secure by default" posture oauth_clients'
-- require_consent already established (ADR-0017).
--
-- allowed_social_providers as text (JSON array), not a normalized child table — same "text JSON
-- array in v1, normalize later if per-value querying is ever needed" convention
-- oauth_clients.allowed_scopes/redirect_uris already established (TD-ARCH-003); a bounded,
-- currently-two-value provider set doesn't yet justify one.
ALTER TABLE organizations
    ADD COLUMN social_login_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN allowed_social_providers text;
