# ADR-0051: The application connects to Postgres with a role that can only read and write rows, migrations run as another, and every container runs without what it does not need

**Status:** 🟡 Pending review (2026-11-20)

Closes TD-SEC-105, TD-SEC-106 and TD-TEST-022 (the 14th review, `docs/04-security/security-audit-2026-11-20.md`).

## Context

Until this decision the production compose file gave the application the credentials of the role the `postgres` image creates (`POSTGRES_USER`, a superuser that owns every table), and Flyway ran as the same role. Every statement the application could be made to run, through a flaw that does not exist today, ran as the owner of the schema and as a superuser: it could rewrite the audit log, drop a table, read the files of the database host or run a program there. And no container in the compose files dropped a Linux capability, refused privilege gain, mounted its root read-only or limited its processes, so a code-execution bug in the application or in the proxy landed in a container with the defaults.

Neither was a defect in the behaviour the application ships. Both are what a reviewer asks first ("what does the application connect as" and "what can a compromised container do") and both are cheaper to answer before the first customer than after.

## Decision

### 1. Three roles, fixed names

| Role | Who uses it | May |
|---|---|---|
| the bootstrap superuser (`DB_PROD_USER`) | an operator, `backup-postgres.sh`, `restore-postgres.sh`, `provision-db-roles.sh` | everything; the application no longer holds it |
| `clavaris_owner` | Flyway only (`SPRING_FLYWAY_USER`) | owns the database, the schema and every object in it; runs DDL; not a superuser, cannot create roles or databases |
| `clavaris_app` | the application (`DB_DEV_USER`) | `SELECT`, `INSERT`, `UPDATE`, `DELETE` on tables, `USAGE` on sequences; no DDL, no access to `flyway_schema_history`, and **no `UPDATE`, `DELETE` or `TRUNCATE` on the audit log or any of its partitions** |

The names are fixed in `infra/postgres/provision-roles.sql` and in the migration that refers to `clavaris_app`; a name that could change is a name nobody checks. Only the two passwords are variables (`DB_APP_PASSWORD`, `DB_OWNER_PASSWORD`), and `provision-db-roles.sh` refuses a password that is short, has a character it cannot pass to `psql` safely, repeats the other role's or repeats the superuser's.

### 2. The roles are provisioned on every deploy, by one idempotent script

`deploy.sh` runs `provision-db-roles.sh` after PostgreSQL is up and before the application starts, and `restore-postgres.sh` runs it after `pg_restore` (which uses `--no-owner`, so the restored objects belong to the superuser). One path for a new host, an existing host and a restored database; no init script that runs only on an empty data directory and so could not help the host that already runs. The script moves ownership of objects created before this change to `clavaris_owner`, sets `ALTER DEFAULT PRIVILEGES FOR ROLE clavaris_owner` so a table a migration creates later is usable by the application without a grant of its own, applies the revocations above again, and applies a rotated password. The passwords reach `psql` on its standard input, never as a command-line argument.

### 3. The two statements the application used to run as DDL become two functions

The maintenance job created a monthly partition of `audit_events` and dropped expired ones with `CREATE TABLE ... PARTITION OF` and `DROP TABLE`, which need ownership. They are now `audit_events_ensure_partition(date)` and `audit_events_drop_partition(date)`, `SECURITY DEFINER` functions owned by the migration role, with `search_path` pinned. They take a date and nothing else (the name is derived with `%I` and the bounds written with `%L`); the first refuses months before 2000 and more than five years ahead and revokes `UPDATE`, `DELETE` and `TRUNCATE` on the new partition from the application role; the second refuses the current month and any later one (the rule the job already stated, now enforced by the database's own clock) and anything that is not one of `audit_events`' own partitions, the default partition included. The application can therefore do exactly what the job needs and a compromised application can do no more.

### 4. Containers

Every service in `docker-compose.prod.yml` and `docker-compose.monitoring.yml` gets `cap_drop: [ALL]` and `security_opt: [no-new-privileges:true]` (one YAML anchor), a `pids_limit`, and, in the production file, a `mem_limit` and `read_only: true` with a `tmpfs` where the image needs one. Each service adds back only what its image needs to start:

| Service | Capabilities added back | Why |
|---|---|---|
| `postgres` | `CHOWN`, `DAC_OVERRIDE`, `FOWNER`, `SETGID`, `SETUID` | the entrypoint chowns the data directory and drops to the postgres user |
| `redis`, `redis-counters` | `CHOWN`, `SETGID`, `SETPCAP`, `SETUID` | the entrypoint chowns `/data` and drops to the redis user with `setpriv`, which needs `SETPCAP` to narrow the bounding set (found by starting the image without it: it exits at once) |
| `caddy` | `NET_BIND_SERVICE` | binds 80 and 443 |
| `app` | none | runs as uid 10001 on 8080; writes `/tmp` (a 256 MB `tmpfs`, `nosuid`) and `/app/data` (the signing-key volume) |

`/tmp` is **not** `noexec` for the application, because the JVM may map a native library it extracts there; if a measurement shows none is used, that is a further step. Postgres had no memory limit and now has 1 GB (`POSTGRES_MEM_LIMIT`), Caddy 256 MB (`CADDY_MEM_LIMIT`).

### 5. A guard

`scripts/check-container-hardening.sh` resolves the compose files with `docker compose config` and fails when a service loses any of the above, adds a capability it is not allowed, runs privileged or on the host network, mounts the Docker socket, or when the application is configured with the superuser or one password for both roles. `scripts/test-check-container-hardening.sh` removes each property from a copy and expects the failure; both run in the `lint` job. `DatabaseRolesProvisioningIntegrationTest` runs the real SQL against a real Postgres, in the state of a host that already runs (every migration applied as the superuser) and then as the first deploy after this change.

## Verified

- Against PostgreSQL 16: the integration test above (roles and attributes, ownership of every table, function and the database, the application's privileges on every table, a later migration's table usable without a grant, the application unable to create, alter or drop anything, unable to `COPY ... PROGRAM`, read `pg_authid`, `SET ROLE` or read the migration history, the audit log and every partition append-only, the two functions' guards, a rotated password).
- With the real images: `postgres:16`, `redis:8` and `caddy:2-alpine` (the pinned digests) started with the flags above and served; the application image started with `cap_drop: ALL`, `no-new-privileges`, a read-only root, a 256 MB `tmpfs` on `/tmp` and a limit of 1024 processes, reached `UP` for the database and Redis, wrote its signing key to the volume and served its pages, with 63 processes (threads) in use.

### Found afterwards (2026-11-24, TD-PROC-041)

The verification above started the images and served, but did not run a backup or a restore. Both scripts moved the dump with `docker cp` through `/tmp`, which this decision made a tmpfs under a read-only root; `docker cp` can read neither, so the first backup after pre-production's Postgres was recreated with the hardening failed. The scripts now stream the dump through standard output and standard input, and `test-backup-restore-hardened.sh` runs them against a real hardened container. A hardening change to a service that scripts talk to is verified by running those scripts against it, not only by seeing it start.

## What a deploy does and does not apply

The application and Caddy are recreated by the deploy because their configuration changed. **Postgres and the two Redis services are not**: a deploy never recreates a stateful service (TD-PROC-021, so that a failed release cannot take the database down with it), so their containers keep the old flags until an operator recreates them once (`deployment-runbook.md` section 6i). `deploy.sh` says so on every deploy while any of them is still writable (`HostConfig.ReadonlyRootfs` is false), and does nothing else about it. The database roles do not wait for that: they are in the database, not in the container.

## Not done, and why

- **Pre-production has not been deployed with this.** The first deploy needs `DB_APP_PASSWORD` and `DB_OWNER_PASSWORD` in `.env` (deploy.sh refuses before pulling anything if they are missing) and runs the provisioning once; see `deployment-runbook.md` §6i.
- **The audit tables' retention sweep** is unchanged and does not need a privilege the application lacks (it drops whole partitions through the function).
- **Other tables that are append-only in spirit** (login events, outbox archives) keep `UPDATE` and `DELETE`, because the code updates and deletes them on purpose. Which others should be append-only is a product decision, not a hardening step.
- **A third role for backup** (a `pg_dump` that is not the superuser) and **TLS to Postgres** are not part of this. Both are cheap on a single host with an internal Docker network, and both are worth doing when the database leaves the host (TD-FUT-049).
- **`noexec` on `/tmp`**, **seccomp and AppArmor profiles of our own** (the Docker default applies) and **a rootless Docker daemon** are the next steps in the same direction.
- **Redis authentication** is TD-SEC-107 and the edge's request-size limit is TD-SEC-108.

## Consequences

- A host must have the two passwords in `.env` before the next deploy; `.env.example` names them and `sync-deploy-files.sh` reports them as missing.
- A new migration needs nothing special: the owner creates the table and the default privileges give the application its rows. A migration that must be usable only by the owner, or a table that must be append-only for the application, says so in its own SQL (the audit log does) and in `provision-roles.sql`.
- A developer's local database is unchanged (`docker-compose.yml` still uses one role); the migration that names `clavaris_app` skips that statement when the role does not exist.
- Rollback to the previous image keeps working: the roles are in the database and the compose file, and the old image only reads and writes rows.
