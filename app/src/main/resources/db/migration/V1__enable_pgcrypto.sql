-- Baseline migration — proves the Flyway wiring works end-to-end before any
-- bounded context owns real schema yet (data-model.md §4).
-- pgcrypto is enabled once, project-wide, since every table's primary key is
-- a uuid (data-model.md §1) — this is the standard place to guarantee that's
-- available regardless of which module's first real migration lands next.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
