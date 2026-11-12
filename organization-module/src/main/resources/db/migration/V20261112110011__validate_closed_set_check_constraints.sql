-- TD-ARCH-054: reads the rows that were already in these tables and marks the constraints added by
-- the previous migration as validated. Its own migration so its lock is held for the scan alone;
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE, which lets reads and writes carry on.
--
-- If this fails, a row holds a value the code never writes. Find it with, for each table and column
-- below, SELECT col, count(*) FROM tbl GROUP BY col; the run is rolled back and nothing is lost.
ALTER TABLE access_restriction_entries VALIDATE CONSTRAINT ck_access_restriction_entries_type;
ALTER TABLE organization_logos VALIDATE CONSTRAINT ck_organization_logos_content_type;
ALTER TABLE organization_social_connection_settings VALIDATE CONSTRAINT ck_organization_social_connection_settings_provider;
ALTER TABLE organization_social_credentials VALIDATE CONSTRAINT ck_organization_social_credentials_provider;
