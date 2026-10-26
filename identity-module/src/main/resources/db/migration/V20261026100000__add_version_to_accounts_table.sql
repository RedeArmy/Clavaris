-- Optimistic locking for the Account aggregate (TD-ARCH-037). AccountRepository#save writes every
-- column of whatever the caller loaded, so two writers that loaded the same account (an operator
-- suspending it while another edits the profile, or a consuming application's backend using the
-- admin API) silently overwrote each other. With a version, the second writer is refused instead.
-- Same shape as the version columns on oauth_clients and the other client tables.
ALTER TABLE accounts ADD COLUMN version integer NOT NULL DEFAULT 0;
