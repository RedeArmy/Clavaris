-- ADR-0024 §4: an account's optional, additional sign-up/sign-in identifier. Nullable, own column
-- — never a nullable field bolted into some other shape, same "just add the scalar column"
-- precedent email_verified_at already establishes for this same table.
ALTER TABLE accounts ADD COLUMN username varchar(32);

-- Partial unique index — most rows have username IS NULL and never participate in this constraint
-- at all (Postgres never enforces uniqueness among NULLs), so this is safe to add without a
-- backfill. Scoped by (organization_id, username), same ADR-0010 tenant-isolation shape
-- ux_accounts_organization_id_email already establishes for the account's other identifier.
CREATE UNIQUE INDEX ux_accounts_organization_id_username
    ON accounts (organization_id, username)
    WHERE username IS NOT NULL;
