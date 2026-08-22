-- ADR-0012: PlatformAccount (identity-module) — a human, self-service identity that owns zero or
-- more Organizations. Single-owner model: exactly one owner per Organization, one owner may own
-- several Organizations.
--
-- Plain UUID column, NOT a foreign key to platform_accounts: same deliberate cross-module-FK gap
-- already recorded on accounts.organization_id's own migration comment — organization-module and
-- identity-module stay mutually independent at the schema level too, referential integrity for
-- this column is enforced at the application layer only.
--
-- NOT NULL from the start, no backfill step needed: no production data exists yet at this project
-- phase (documentation-first, per CLAUDE.md §11).
ALTER TABLE organizations
    ADD COLUMN owner_platform_account_id uuid NOT NULL;

CREATE INDEX ix_organizations_owner_platform_account_id ON organizations (owner_platform_account_id);
