-- ADR-0031: the hosted sign-up always offers a username, so the per-Organization switch that used to
-- decide it no longer controls anything. The application stopped reading and writing the column in
-- the same release; this removes it. Dropping a column of a small, per-Organization settings table
-- takes its lock for an instant.
ALTER TABLE account_authentication_policies DROP COLUMN username_sign_up_enabled;
