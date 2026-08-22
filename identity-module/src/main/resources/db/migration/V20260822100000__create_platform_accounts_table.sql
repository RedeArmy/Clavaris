-- ADR-0012: PlatformAccount — a human, self-service identity at the Clavaris platform level
-- itself, owning zero or more organizations (V20260822100003 adds organizations.owner_platform_
-- account_id). Deliberately no organization_id column, not even nullable — same rationale as
-- platform_clients' own migration comment: this identity authenticates the operations that
-- create/manage Organization rows, so belonging to one would be circular.
CREATE TABLE platform_accounts (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email              varchar(320) NOT NULL,
    email_verified_at  timestamptz,
    status             varchar(20) NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now()
);

-- Global uniqueness, unlike accounts.(organization_id, email) — there is no Organization to scope
-- a PlatformAccount's email by.
CREATE UNIQUE INDEX ux_platform_accounts_email ON platform_accounts (email);
