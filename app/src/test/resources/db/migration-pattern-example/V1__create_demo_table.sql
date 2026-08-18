-- Worked example only — NOT part of the real migration history
-- (app/src/main/resources/db/migration). Exists purely so
-- MigrationDataPreservationTest has a concrete before/after schema to prove
-- the "seed data, migrate, assert it survived" pattern against.
CREATE TABLE migration_pattern_demo (
    id UUID PRIMARY KEY,
    legacy_status VARCHAR(20) NOT NULL
);
