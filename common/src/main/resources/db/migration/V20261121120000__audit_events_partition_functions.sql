-- TD-SEC-106: the application no longer owns the schema, so it cannot run the DDL that keeps the audit
-- log's monthly partitions (CREATE TABLE ... PARTITION OF, DROP TABLE). Those two statements, and
-- only those, move into functions owned by the role that runs migrations (SECURITY DEFINER): the
-- application calls them, and a code-execution bug in the application can do no more than they allow.
--
-- Both are strict about what they accept, because DDL cannot take bind parameters and the name is
-- built here: the argument is a date, never text, the table name is derived from it with %I, and the
-- bounds are literals written with %L. search_path is pinned so nothing in another schema can stand in
-- for a table or function this body names.
--
-- audit_events_ensure_partition is idempotent (IF NOT EXISTS), like the statement it replaces, and
-- makes the new partition append-only for the runtime role (no UPDATE, DELETE or TRUNCATE).
-- audit_events_drop_partition refuses the current month and any later one, which is the rule the
-- maintenance job already states ("the current and future months are never dropped"), and refuses
-- anything that is not one of audit_events' own partitions, the default partition included.
--
-- clavaris_app is the runtime role provision-roles.sql creates. Where it does not exist (a developer's
-- database, a test) the statements that name it are skipped and the functions work for whoever calls
-- them; EXECUTE is revoked from PUBLIC either way and granted to the role by provision-roles.sql.

CREATE FUNCTION audit_events_ensure_partition(p_month date) RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    month_start date := date_trunc('month', p_month::timestamp)::date;
    this_month  date := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
    part        text;
BEGIN
    -- A bound on how far ahead, so a bug cannot create partitions without end.
    IF month_start < date '2000-01-01' OR month_start > this_month + interval '5 years' THEN
        RAISE EXCEPTION 'audit_events_ensure_partition: % is outside the months a partition may be created for', p_month;
    END IF;
    part := format('audit_events_%s', to_char(month_start, 'YYYY_MM'));
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_events FOR VALUES FROM (%L) TO (%L)',
        part,
        month_start::text || ' 00:00:00+00',
        (month_start + interval '1 month')::date::text || ' 00:00:00+00');
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'clavaris_app') THEN
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM clavaris_app', part);
    END IF;
    RETURN part;
END
$$;

CREATE FUNCTION audit_events_drop_partition(p_month date) RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    month_start date := date_trunc('month', p_month::timestamp)::date;
    this_month  date := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
    part        text;
BEGIN
    IF month_start >= this_month THEN
        RAISE EXCEPTION 'audit_events_drop_partition: the current and future months are never dropped (%)', p_month;
    END IF;
    part := format('audit_events_%s', to_char(month_start, 'YYYY_MM'));
    IF NOT EXISTS (SELECT 1
                     FROM pg_inherits i
                     JOIN pg_class c ON c.oid = i.inhrelid
                    WHERE i.inhparent = 'public.audit_events'::regclass
                      AND c.relname = part) THEN
        RAISE EXCEPTION 'audit_events_drop_partition: % is not a partition of audit_events', part;
    END IF;
    EXECUTE format('DROP TABLE %I', part);
    RETURN part;
END
$$;

REVOKE ALL ON FUNCTION audit_events_ensure_partition(date) FROM PUBLIC;
REVOKE ALL ON FUNCTION audit_events_drop_partition(date) FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'clavaris_app') THEN
        GRANT EXECUTE ON FUNCTION audit_events_ensure_partition(date) TO clavaris_app;
        GRANT EXECUTE ON FUNCTION audit_events_drop_partition(date) TO clavaris_app;
    END IF;
END
$$;
