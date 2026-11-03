-- ADR-0035: opt-in per Organization, off by default (zero regression) — a password sign-in must
-- also present an emailed one-time code, every time, not only from a new device.
ALTER TABLE account_authentication_policies
    ADD COLUMN code_after_password_required boolean NOT NULL DEFAULT false;
