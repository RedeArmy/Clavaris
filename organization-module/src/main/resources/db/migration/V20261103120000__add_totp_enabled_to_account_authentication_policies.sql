-- ADR-0040: opt-in per Organization, off by default (zero regression) — the accounts of an
-- Organization may enrol an authenticator app, and an enrolled account is asked for its code at
-- every sign-in that is not already a passkey.
ALTER TABLE account_authentication_policies
    ADD COLUMN totp_enabled boolean NOT NULL DEFAULT false;
