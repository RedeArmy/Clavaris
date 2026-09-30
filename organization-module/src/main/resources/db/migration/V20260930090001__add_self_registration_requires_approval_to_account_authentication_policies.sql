-- TD-FUT-019: gated self-registration for a consuming application's own public end users, opt-in
-- per Organization — off by default (opt-in, zero regression), same shape device_trust_enabled
-- already establishes on this same table.
ALTER TABLE account_authentication_policies
    ADD COLUMN self_registration_requires_approval boolean NOT NULL DEFAULT false;
