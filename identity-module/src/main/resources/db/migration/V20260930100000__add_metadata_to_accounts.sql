-- Plain text, not jsonb — same "never mapped as a native jsonb column type" convention
-- SessionEntity.scopes already establishes for this codebase's own JPA entities; syntactic JSON
-- validity is enforced at the use-case layer (UpdateAccountMetadataService), not by the database.
ALTER TABLE accounts
    ADD COLUMN public_metadata text,
    ADD COLUMN private_metadata text,
    ADD COLUMN unsafe_metadata text;
