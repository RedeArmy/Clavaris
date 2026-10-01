-- TD-FUT-019: gated self-registration's own approve/reject decision bookkeeping. Nullable, no
-- default, same "absence means never happened" shape password_reset_required_at already
-- establishes for this table — the overwhelming majority of accounts were never gated in the
-- first place. registration_rejection_reason is only ever set alongside a REJECTED status.
ALTER TABLE accounts
    ADD COLUMN registration_decided_at timestamptz,
    ADD COLUMN registration_rejection_reason text;
