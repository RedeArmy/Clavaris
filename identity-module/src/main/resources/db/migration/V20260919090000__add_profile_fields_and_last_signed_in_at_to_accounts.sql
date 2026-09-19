-- SDE-III review, 2026-09-19: dashboard "Users" tab (Clerk parity) — first_name/last_name/
-- phone_number are the optional profile fields the admin create-user form collects; last_signed_in_at
-- is updated by every successful password/social authentication (Account.recordSignIn()). All four
-- nullable — the overwhelming majority of existing rows have none of them, same precedent as
-- username/password_reset_required_at's own additive migrations on this table.
ALTER TABLE accounts
    ADD COLUMN first_name varchar(255),
    ADD COLUMN last_name varchar(255),
    ADD COLUMN phone_number varchar(32),
    ADD COLUMN last_signed_in_at timestamptz;
