-- The approval queue (dashboard Users, Pending approval) reads only PENDING_APPROVAL rows of one
-- Organization, oldest first. A partial index holds just those rows, so it stays tiny however many
-- Accounts an Organization has and costs nothing for the ones already decided.
CREATE INDEX ix_accounts_pending_approval
    ON accounts (organization_id, created_at, id)
    WHERE status = 'PENDING_APPROVAL';
