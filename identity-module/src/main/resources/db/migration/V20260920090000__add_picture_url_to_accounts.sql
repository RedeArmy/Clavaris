-- ADR-0026: profile picture support. Nullable — null means "use the generated-initials default
-- avatar" (GetAccountAvatarService's own fallback), not an error state; the overwhelming majority
-- of existing rows have no value here, same "just add the scalar column" precedent every prior
-- additive migration on this table already establishes (username, password_reset_required_at,
-- first_name/last_name/phone_number/last_signed_in_at).
ALTER TABLE accounts
    ADD COLUMN picture_url text;
