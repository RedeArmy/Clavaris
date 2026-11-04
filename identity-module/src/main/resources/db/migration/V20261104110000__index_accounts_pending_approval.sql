-- The approval queue (dashboard Users, Pending approval) reads only PENDING_APPROVAL rows of one
-- Organization, oldest first. A partial index holds just those rows, so it stays tiny however many
-- Accounts an Organization has and costs nothing for the ones already decided.
--
-- Built CONCURRENTLY because accounts already holds every Account of every Organization: a blocking
-- build would make every sign-in and registration queue behind it (TD-FUT-039). Flyway runs a
-- migration holding one non-transactionally on its own.
CREATE INDEX CONCURRENTLY ix_accounts_pending_approval
    ON accounts (organization_id, created_at, id)
    WHERE status = 'PENDING_APPROVAL';
