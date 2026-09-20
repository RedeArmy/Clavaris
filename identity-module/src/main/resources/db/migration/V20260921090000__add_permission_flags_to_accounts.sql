-- ADR-0026, Clerk "User permissions" parity: operator-controlled, both default false — an
-- Organization opts a specific Account into either, never on by default. NOT NULL with a default
-- (unlike the nullable profile columns on this same table) since these are genuine booleans, not
-- "unset means something different from false" fields.
ALTER TABLE accounts
    ADD COLUMN can_delete_own_account boolean NOT NULL DEFAULT false,
    ADD COLUMN bypasses_device_trust boolean NOT NULL DEFAULT false;
