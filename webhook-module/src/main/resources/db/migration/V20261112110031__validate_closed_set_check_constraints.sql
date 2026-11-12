-- TD-ARCH-054: reads the rows that were already in these tables and marks the constraints added by
-- the previous migration as validated. Its own migration so its lock is held for the scan alone;
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE, which lets reads and writes carry on.
--
-- If this fails, a row holds a value the code never writes. Find it with, for each table and column
-- below, SELECT col, count(*) FROM tbl GROUP BY col; the run is rolled back and nothing is lost.
ALTER TABLE webhook_deliveries VALIDATE CONSTRAINT ck_webhook_deliveries_status;
