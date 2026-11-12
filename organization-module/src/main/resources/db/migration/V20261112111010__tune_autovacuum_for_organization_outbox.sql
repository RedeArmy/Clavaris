-- TD-PERF-065: the Organization event outbox, written by every state change and swept by its retention job.
-- The default autovacuum_vacuum_scale_factor of 0.2 waits until a fifth of a table is dead rows, which
-- on a table of millions is hundreds of thousands of dead tuples (and the index bloat that goes with
-- them) before the first vacuum. These tables are written and then deleted in bulk by the retention
-- jobs, so they are where it shows. Vacuum and analyze after 2 percent of the rows have changed, with
-- a floor of 1,000 so a nearly empty table is not vacuumed all day.
-- SET (...) takes SHARE UPDATE EXCLUSIVE, which lets reads and writes carry on.
ALTER TABLE organization_event_outbox SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_vacuum_threshold = 1000,
    autovacuum_analyze_scale_factor = 0.02
);
