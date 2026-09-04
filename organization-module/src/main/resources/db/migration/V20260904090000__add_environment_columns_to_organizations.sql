-- SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis):
-- environment distinguishes a sandboxed Organization from a real, unbounded one — see
-- OrganizationEnvironment's own Javadoc for the full design rationale.
--
-- DEFAULT 'PRODUCTION': every Organization that already exists before this concept shipped is
-- already being used, unsandboxed, as a real tenant — nothing about its behavior changes
-- retroactively. Organization.register() (the Java-side default for every NEW Organization going
-- forward) explicitly constructs DEVELOPMENT instead; this SQL default only ever protects
-- already-existing rows.
ALTER TABLE organizations
    ADD COLUMN environment varchar(20) NOT NULL DEFAULT 'PRODUCTION';

-- Self-reference, not a FK to any other table — the paired sibling Organization (dev -> its
-- production promotion, and back). Nullable: most Organizations never have one, matching
-- Organization.linkedEnvironmentOrganizationId()'s own Optional accessor.
ALTER TABLE organizations
    ADD COLUMN linked_environment_organization_id uuid NULL REFERENCES organizations (id);
