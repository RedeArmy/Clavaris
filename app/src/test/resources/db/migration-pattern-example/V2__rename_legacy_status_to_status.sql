-- Worked example only — NOT part of the real migration history. Correct
-- approach for renaming a column with existing data: an actual RENAME,
-- never drop-then-recreate (which silently destroys every existing row's
-- value — see MigrationDataPreservationTest, which fails against that
-- version of this migration and passes against this one).
ALTER TABLE migration_pattern_demo RENAME COLUMN legacy_status TO status;
