-- Code review finding: social_identities/pending_social_links (account_id) and
-- platform_social_identities/pending_platform_social_links (platform_account_id) shipped without
-- ON DELETE CASCADE, breaking the precedent V20260826100000 established specifically so no future
-- table would be silently left off — DeleteAccountService's own Javadoc named this exact gap
-- explicitly ("Step 3 (delete SocialIdentity links) remains narrower than that design... must be
-- revisited the day it ships") and it was never revisited when social login shipped.
--
-- Without this, a hard-delete of any Account that ever linked a social identity, or has an
-- unconfirmed pending link, fails with a bare FK-violation instead of completing — breaking the
-- "hard delete is final" guarantee BR-DATA-02/03 requires. Same gap for platform_accounts, even
-- though no delete-platform-account use case exists yet: fixed now, structurally, rather than
-- left for whichever future feature adds one to rediscover.
--
-- Constraint names follow Postgres's own default <table>_<column>_fkey convention — none of the
-- four original migrations named them explicitly, same as every table V20260826100000 already
-- fixed.
ALTER TABLE social_identities DROP CONSTRAINT social_identities_account_id_fkey;
ALTER TABLE social_identities
    ADD CONSTRAINT social_identities_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE;

ALTER TABLE pending_social_links DROP CONSTRAINT pending_social_links_account_id_fkey;
ALTER TABLE pending_social_links
    ADD CONSTRAINT pending_social_links_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE;

ALTER TABLE platform_social_identities DROP CONSTRAINT platform_social_identities_platform_account_id_fkey;
ALTER TABLE platform_social_identities
    ADD CONSTRAINT platform_social_identities_platform_account_id_fkey
    FOREIGN KEY (platform_account_id) REFERENCES platform_accounts (id) ON DELETE CASCADE;

ALTER TABLE pending_platform_social_links DROP CONSTRAINT pending_platform_social_links_platform_account_id_fkey;
ALTER TABLE pending_platform_social_links
    ADD CONSTRAINT pending_platform_social_links_platform_account_id_fkey
    FOREIGN KEY (platform_account_id) REFERENCES platform_accounts (id) ON DELETE CASCADE;
