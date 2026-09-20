-- ADR-0026: Clerk manage-account parity for PlatformAccount, same shape as accounts' own
-- first_name/last_name/picture_url columns (V20260919090000, V20260920090000). Nullable — the
-- overwhelming majority of existing rows have none of them.
ALTER TABLE platform_accounts
    ADD COLUMN first_name varchar(255),
    ADD COLUMN last_name varchar(255),
    ADD COLUMN picture_url text;
