-- data-model.md §2/§3: identity-module's first real table. Schema ships with its owning use
-- case (RegisterAccount), not pre-created ahead of it.
--
-- organization_id is a plain UUID column, NOT a foreign key to an `organizations` table: that
-- table doesn't exist yet (organization-module has no migrations/domain code as of this commit).
-- Adding the FK constraint is a tracked follow-up for whichever change first
-- creates `organizations` (organization-module's own first vertical slice) — until then,
-- referential integrity for organization_id is enforced only at the application layer. This is a
-- real, deliberate gap, not an oversight: recorded here so it isn't silently forgotten.
CREATE TABLE accounts (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    uuid NOT NULL,
    email              varchar(320) NOT NULL,
    email_verified_at  timestamptz,
    status             varchar(20) NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now()
);

-- ADR-0010: uniqueness is (organization_id, email), never a global email constraint — the same
-- email may exist as an entirely unrelated Account in a different Organization.
CREATE UNIQUE INDEX ux_accounts_organization_id_email ON accounts (organization_id, email);
