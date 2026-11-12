-- TD-ARCH-054: reads the rows that were already in these tables and marks the constraints added by
-- the previous migration as validated. Its own migration so its lock is held for the scan alone;
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE, which lets reads and writes carry on.
--
-- If this fails, a row holds a value the code never writes. Find it with, for each table and column
-- below, SELECT col, count(*) FROM tbl GROUP BY col; the run is rolled back and nothing is lost.
ALTER TABLE accounts VALIDATE CONSTRAINT ck_accounts_status;
ALTER TABLE platform_accounts VALIDATE CONSTRAINT ck_platform_accounts_status;
ALTER TABLE social_identities VALIDATE CONSTRAINT ck_social_identities_provider;
ALTER TABLE platform_social_identities VALIDATE CONSTRAINT ck_platform_social_identities_provider;
ALTER TABLE pending_social_links VALIDATE CONSTRAINT ck_pending_social_links_provider;
ALTER TABLE pending_platform_social_links VALIDATE CONSTRAINT ck_pending_platform_social_links_provider;
ALTER TABLE verification_tokens VALIDATE CONSTRAINT ck_verification_tokens_type;
ALTER TABLE platform_verification_tokens VALIDATE CONSTRAINT ck_platform_verification_tokens_type;
