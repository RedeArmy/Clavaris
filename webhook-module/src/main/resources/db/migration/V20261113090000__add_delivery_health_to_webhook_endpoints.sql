-- TD-ARCH-053: nothing used to remember that an endpoint never answers, so a dead endpoint cost a
-- full retry run for every event forever. consecutive_failures counts deliveries that used up every
-- attempt since the last one that succeeded; auto_disabled_at marks an endpoint the system switched
-- off at the threshold (null when active, or when a person switched it off). Both are added with a
-- constant default, which Postgres 11+ stores in the catalog without rewriting the table.
ALTER TABLE webhook_endpoints
    ADD COLUMN consecutive_failures integer NOT NULL DEFAULT 0,
    ADD COLUMN auto_disabled_at timestamptz;
