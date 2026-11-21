-- TD-SEC-106: the database roles of a deployment, created or brought back to the intended state.
--
-- Run as the bootstrap superuser (the one the postgres image creates from POSTGRES_USER), connected
-- to the application's database, with the two passwords given as psql variables:
--
--   psql -v ON_ERROR_STOP=1 -v owner_password=... -v app_password=... -d <database> -f provision-roles.sql
--
-- scripts/host/provision-db-roles.sh does exactly that, and deploy.sh runs it on every deploy, so the
-- grants below converge on each release instead of drifting. Safe to run any number of times.
--
-- Three roles, three jobs:
--
--   <bootstrap superuser>  Created by the image. Used by an operator and by backup and restore. The
--                          application never holds it any more.
--   clavaris_owner         Owns the schema and every object in it. Used by Flyway only (DDL). Not a
--                          superuser, cannot create roles or databases.
--   clavaris_app           What the application connects as. SELECT, INSERT, UPDATE and DELETE on the
--                          tables, USAGE on the sequences, nothing else: no DDL, cannot touch the
--                          migration history, and cannot UPDATE or DELETE a row of the audit log
--                          (append-only for the application, the only way to drop a month of it is
--                          audit_events_drop_partition, which refuses the current month).
--
-- The role names are fixed on purpose: db/migration refers to clavaris_app when it creates a function
-- and revokes a privilege, and a name that could change would be a name nobody checks. Only the two
-- passwords come from the environment.

\set ON_ERROR_STOP on

-- A missing password stops the script with an error (psql exits with status 3 under ON_ERROR_STOP);
-- \quit would exit 0, which a caller would read as success.
\if :{?owner_password}
\else
  DO $$ BEGIN RAISE EXCEPTION 'provision-roles.sql: owner_password is not set (psql -v owner_password=...)'; END $$;
\endif
\if :{?app_password}
\else
  DO $$ BEGIN RAISE EXCEPTION 'provision-roles.sql: app_password is not set (psql -v app_password=...)'; END $$;
\endif

-- The roles. CREATE only when missing, then ALTER every time, so a password rotated in .env is
-- applied by the next deploy.
SELECT 'CREATE ROLE clavaris_owner'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'clavaris_owner') \gexec
SELECT format(
         'ALTER ROLE clavaris_owner WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
         :'owner_password') \gexec

SELECT 'CREATE ROLE clavaris_app'
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'clavaris_app') \gexec
SELECT format(
         'ALTER ROLE clavaris_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
         :'app_password') \gexec

-- A schema other than public would be found only through the search path: pin it, so an object an
-- attacker could create in another schema is never resolved ahead of the real one.
ALTER ROLE clavaris_owner SET search_path = public;
ALTER ROLE clavaris_app   SET search_path = public;

-- The database. The owner may install a trusted extension (pgcrypto and pg_trgm, the two this schema
-- uses, are both trusted since PostgreSQL 13) without being a superuser.
SELECT format('ALTER DATABASE %I OWNER TO clavaris_owner', current_database()) \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO clavaris_app', current_database()) \gexec

ALTER SCHEMA public OWNER TO clavaris_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO clavaris_app;

-- Objects created before this script existed belong to the bootstrap superuser. Hand them to the
-- owner so Flyway, which no longer runs as that superuser, can alter them. Objects that belong to an
-- extension are left alone (they move with it), and so are a sequence that belongs to a column (it
-- follows its table; changing it on its own is an error).
DO $$
DECLARE
    owner_oid oid := (SELECT oid FROM pg_roles WHERE rolname = 'clavaris_owner');
    r record;
BEGIN
    FOR r IN
        SELECT c.relkind,
               quote_ident(n.nspname) || '.' || quote_ident(c.relname) AS qname
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relkind IN ('r', 'p', 'v', 'm')
           AND c.relowner <> owner_oid
           AND NOT EXISTS (SELECT 1 FROM pg_depend d
                            WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
         ORDER BY c.oid
    LOOP
        EXECUTE format('ALTER %s %s OWNER TO clavaris_owner',
                       CASE r.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END,
                       r.qname);
    END LOOP;

    FOR r IN
        SELECT quote_ident(n.nspname) || '.' || quote_ident(c.relname) AS qname
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relkind = 'S'
           AND c.relowner <> owner_oid
           AND NOT EXISTS (SELECT 1 FROM pg_depend d
                            WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype IN ('a', 'i', 'e'))
    LOOP
        EXECUTE format('ALTER SEQUENCE %s OWNER TO clavaris_owner', r.qname);
    END LOOP;

    FOR r IN
        SELECT p.prokind, p.oid::regprocedure::text AS signature
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'public'
           AND p.prokind IN ('f', 'p')
           AND p.proowner <> owner_oid
           AND NOT EXISTS (SELECT 1 FROM pg_depend d
                            WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
    LOOP
        EXECUTE format('ALTER %s %s OWNER TO clavaris_owner',
                       CASE r.prokind WHEN 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END, r.signature);
    END LOOP;

    FOR r IN
        SELECT t.typtype, quote_ident(n.nspname) || '.' || quote_ident(t.typname) AS qname
          FROM pg_type t
          JOIN pg_namespace n ON n.oid = t.typnamespace
         WHERE n.nspname = 'public'
           AND t.typtype IN ('e', 'd')
           AND t.typowner <> owner_oid
           AND NOT EXISTS (SELECT 1 FROM pg_depend d
                            WHERE d.classid = 'pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e')
    LOOP
        EXECUTE format('ALTER %s %s OWNER TO clavaris_owner',
                       CASE r.typtype WHEN 'd' THEN 'DOMAIN' ELSE 'TYPE' END, r.qname);
    END LOOP;
END
$$;

-- What the application may do: read and write rows, use sequences. Both for what exists now and, with
-- the default privileges below, for every table and sequence the owner creates later, so a new
-- migration needs no grant of its own.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO clavaris_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO clavaris_app;
ALTER DEFAULT PRIVILEGES FOR ROLE clavaris_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO clavaris_app;
ALTER DEFAULT PRIVILEGES FOR ROLE clavaris_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO clavaris_app;

-- What it may not do.
--
-- The migration history is Flyway's, not the application's.
DO $$
BEGIN
    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        REVOKE ALL ON public.flyway_schema_history FROM clavaris_app;
    END IF;
END
$$;

-- The audit log is append-only for the application: it inserts and reads, it does not rewrite or
-- delete. The parent and every partition, including the default one.
DO $$
DECLARE
    audit regclass := to_regclass('public.audit_events');
    r record;
BEGIN
    IF audit IS NULL THEN
        RETURN;
    END IF;
    FOR r IN
        SELECT audit::text AS t
        UNION ALL
        SELECT inhrelid::regclass::text FROM pg_inherits WHERE inhparent = audit
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %s FROM clavaris_app', r.t);
    END LOOP;
END
$$;

-- The two operations on partitions the application is allowed, as functions the migration created
-- and the owner owns (SECURITY DEFINER), so the application itself needs no DDL privilege.
DO $$
BEGIN
    IF to_regprocedure('public.audit_events_ensure_partition(date)') IS NOT NULL THEN
        GRANT EXECUTE ON FUNCTION public.audit_events_ensure_partition(date) TO clavaris_app;
    END IF;
    IF to_regprocedure('public.audit_events_drop_partition(date)') IS NOT NULL THEN
        GRANT EXECUTE ON FUNCTION public.audit_events_drop_partition(date) TO clavaris_app;
    END IF;
END
$$;

\echo 'provision-roles.sql: roles clavaris_owner (migrations) and clavaris_app (runtime) are in place'
