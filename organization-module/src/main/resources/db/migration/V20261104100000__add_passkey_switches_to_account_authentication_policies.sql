-- ADR-0041: two Organization switches for passkeys, both ON by default so that no existing
-- Organization changes behaviour. passkeys_enabled allows passkey sign-in and registration at all;
-- passkey_offer_enabled controls the page offered after a sign-in by emailed code or link, and has
-- no effect while passkeys_enabled is off.
ALTER TABLE account_authentication_policies
    ADD COLUMN passkeys_enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN passkey_offer_enabled boolean NOT NULL DEFAULT true;
