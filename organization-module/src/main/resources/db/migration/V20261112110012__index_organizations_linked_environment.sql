-- TD-ARCH-054: organizations.linked_environment_organization_id references organizations, and
-- Postgres does not index the referencing side of a foreign key. Without an index, deleting an
-- Organization (the referenced side) scans this table for rows that point at it.
-- Partial: most Organizations are not linked to another environment.
-- CONCURRENTLY, because the table can already hold rows and a plain build would block its writers.
CREATE INDEX CONCURRENTLY ix_organizations_linked_environment
    ON organizations (linked_environment_organization_id)
    WHERE linked_environment_organization_id IS NOT NULL;
