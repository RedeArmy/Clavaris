-- Clerk "session tasks" parity: an operator-forced "must set a new password before this account
-- may finish signing in again" marker. Nullable, own column — never a nullable field bolted into
-- some other shape, same "just add the scalar column" precedent email_verified_at/username already
-- establish for this same table. Absence (the overwhelming common case) means never forced.
ALTER TABLE accounts ADD COLUMN password_reset_required_at timestamptz;
