# Deployment Runbook — Clavaris

🟡 En revisión

## 1. Scope and what this is (TD-FUT-013, ADR-0018)

A single-VM production deployment — `docker-compose.prod.yml`, a production-hardened variant of
the same `docker-compose.yml` every developer already runs locally, plus Caddy for TLS. Deliberately
not Kubernetes/Helm/Terraform: `nfr-quality-attributes.md` §3's own expected v1 load (single-digit
consuming applications) doesn't yet justify multi-instance orchestration, and `TD-FUT-004` (the
architectural blocker to horizontal scaling — stateless sessions via Redis, no in-memory state) is
already closed, so moving to a real orchestrator later is additive work, not a rewrite, the day
traffic actually justifies it. `ADR-0018` is the full comparison behind every tooling choice in
this runbook (Caddy vs. nginx vs. Apache, `.env` vs. mandatory Infisical, why not Terraform yet) —
this document is the "how," that ADR is the "why."

**What this gives you**: a real, reachable, TLS-terminated instance for a consuming application
(JobSeeker) to integrate against, and a real target for the mandatory external security review to
review — the two things `roadmap-and-release-plan.md` §2's own exit criterion names.

**What this deliberately does not give you**: zero-downtime rolling deploys, auto-scaling, or
multi-instance high availability. A `docker compose pull && up -d` briefly stops and restarts the
`app` container — acceptable at v1's own stated scale, not acceptable indefinitely. Revisit this
runbook (or replace it with a real orchestrator) the day that stops being true.

## 2. Prerequisites

- A VM (any provider) with a public IP, Docker and the Docker Compose plugin installed.
- **Minimum 3 vCPU / 4GB RAM** (TD-PERF-007) — see §2a below for why this specific floor, not a
  round number picked without reasoning.
- A DNS `A` record for the domain this instance will be reachable at, already pointing at that
  public IP before the first `docker compose up` — Let's Encrypt's own HTTP-01 challenge (Caddy
  runs this automatically) fails if the domain doesn't resolve yet.
- Ports 80 and 443 reachable from the internet (security group / firewall rule) — Caddy needs 80
  for the ACME challenge and the automatic HTTP→HTTPS redirect, 443 for everything else.
- A GitHub Container Registry image to pull: `ci.yml`'s own `docker-build` job pushes
  `ghcr.io/<owner>/clavaris:latest` (and `:<git-sha>`) automatically on every push to `master` — no
  separate publish step to run by hand.

### 2a. Capacity tuning (TD-PERF-007)

Three related numbers, sized together against the 3-vCPU reference machine TD-FUT-017's own real
load test already measured this system's real bottleneck against (Argon2id verification on
`/oauth2/token`, not Postgres or Redis) — none of them should be tuned in isolation from the
others:

| Setting | Env var | Default | Reasoning |
|---|---|---|---|
| Tomcat max threads | `TOMCAT_MAX_THREADS` | 50 | Bounds worst-case concurrent Argon2id memory (~19MiB/verification) to a number the JVM heap below is sized to survive, while still leaving room for fast, non-Argon2 endpoints (JWKS, health checks) to not queue behind it artificially. |
| HikariCP pool size | `DB_HIKARI_MAX_POOL_SIZE` | 10 | HikariCP's own `((core_count * 2) + spindle_count)` sizing guidance for a 3-vCPU host. Raise roughly in step with real core count on a bigger VM, not independently of it. |
| HikariCP connection timeout | `DB_HIKARI_CONNECTION_TIMEOUT_MS` | 10000 | Fails fast and loudly instead of Spring Boot's own 30s default silent queue — live-caught by this app's own full test suite that 5s (this row's own first attempt) was aggressive enough to convert genuine-but-transient contention into hard failures; 10s still fails an order of magnitude faster than the 30s default while giving that contention room to actually clear. |
| JVM heap | `JAVA_OPTS` (`app/docker-entrypoint.sh`) | `-Xmx1536m -Xms512m` | Covers worst-case Argon2 memory (50 × ~19MiB ≈ 950MiB) plus normal heap/GC headroom. |
| Container memory limit | `mem_limit` (`docker-compose.prod.yml`, `app` service) | `2g` | Headroom above the JVM heap ceiling for metaspace/thread stacks/native buffers — an unbounded container previously let a leak or genuine worst-case burst consume the whole host instead of failing this one container loudly (`restart: unless-stopped` brings it back). |

On a bigger VM: raise Tomcat threads and the Hikari pool together (roughly in proportion to real
core count), then raise the JVM heap/container memory to match the new worst-case Argon2 memory
bill (`threads × ~19MiB`, plus headroom) — never just one of the five in isolation.

**Under `docker-compose.prod.yml` specifically**, the three Spring-consumed env vars above
(`TOMCAT_MAX_THREADS`, `DB_HIKARI_MAX_POOL_SIZE`, `DB_HIKARI_CONNECTION_TIMEOUT_MS`) are
deliberately **not** wired into that file's own `app.environment` block — same as the pre-existing
`EVENT_OUTBOX_RETENTION_DAYS`, setting one in `.env` alone has no effect through this specific
deployment path today, and that's intentional, not an oversight: Spring's own YAML
`${VAR:default}` placeholder only substitutes its default when the property is genuinely *absent*,
not when it's present-but-empty — and Compose has no clean way to pass a host env var through only
when it's actually set (the safe `${VAR:-}` pattern below, used for `JAVA_OPTS`, would instead pass
an *empty string* into the container for any of these three, breaking Spring's own int/duration
binding at startup the moment `.env` doesn't define it, which is the common case). To override one
of these three, add its line to `docker-compose.prod.yml`'s own `environment:` list directly
(`TOMCAT_MAX_THREADS: ${TOMCAT_MAX_THREADS:?...}` or a literal value) — a deliberate, visible file
edit, not a blank-`.env`-value silently doing nothing or silently breaking startup.

`JAVA_OPTS` doesn't share that risk and *is* wired through (`docker-compose.prod.yml`'s own
`app.environment.JAVA_OPTS: ${JAVA_OPTS:-}`) — it's read by `docker-entrypoint.sh`'s own POSIX
`"${JAVA_OPTS:-default}"`, which treats "unset" and "set to empty string" identically (confirmed
live), so leaving it blank in `.env` correctly falls through to the real default instead of
breaking anything. `mem_limit` is a `docker-compose.prod.yml` file value, not an env var at all —
edited directly in that file.

## 3. First deploy

ADR-0018 §Decision 3: deliberately not a full `git clone` of the monorepo — the production host
only ever needs three files (this app's own source is irrelevant here; `ci.yml` already built and
pushed the real image). `scripts/host/bootstrap.sh` fetches exactly those, installs Docker, creates
a dedicated non-root deploy user, and locks down `.env`'s own file permissions — run once, as root:

```bash
# On the VM, as root:
curl -fsSL https://raw.githubusercontent.com/RedeArmy/Clavaris/master/scripts/host/bootstrap.sh | bash

# Then, still on the VM: fill in every value in /opt/clavaris/.env — every ${VAR:?...} entry
# docker-compose.prod.yml requires fails loudly at "up" time if left blank, by design
# (TD-SEC-013's own "no silent default" posture). Real, unique secrets per environment — never
# copy a value from a developer's own local .env.
sudo -u clavaris nano /opt/clavaris/.env

# As the 'clavaris' user (bootstrap.sh already added it to the docker group):
su - clavaris
cd /opt/clavaris
curl -fsSL https://raw.githubusercontent.com/RedeArmy/Clavaris/master/scripts/host/deploy.sh -o deploy.sh
chmod +x deploy.sh
./deploy.sh
```

`deploy.sh` pulls, starts the new containers, and polls `/actuator/health/readiness` internally
(no network round trip needed) before declaring success — see §4 below for what it does if that
check never passes.

`PLATFORM_BOOTSTRAP_CLIENT_ID`/`SECRET` (BR-PLATFORM-03) are seeded into the database on first
startup only — generate long, random, unique values for this environment before the first `up`,
the same "never reused across dev/staging/prod" discipline every other secret in `.env.example`
already documents. Losing this credential without a backup means no `Organization` can ever be
created against this instance again without a manual database intervention — see
`incident-response-platform-client-compromise.md` for the related (but distinct) compromise
scenario, not a loss scenario.

## 4. Routine deploys (a new commit merged to `master`)

```bash
cd /opt/clavaris && ./deploy.sh
```

One command — pulls, restarts only the containers whose image actually changed
(`postgres`/`redis` stay running untouched), and **auto-rolls-back** if the new `app` container
doesn't report healthy within 90 seconds: `deploy.sh` records the image ID that was running before
the pull, and if the health check never passes, re-tags that previous image back onto the
`docker-compose.prod.yml`-expected tag and brings it back up — a failed deploy self-heals instead
of silently leaving a broken container running, without needing anyone watching in real time. Expect
a brief window (§1's own stated gap) where `app` is unreachable while the new container starts and
passes its own readiness probe, whether or not a rollback ends up happening.

If a rollback fires, `deploy.sh` exits non-zero with a pointer to `docker compose logs app` — fix
the underlying issue and merge a new commit before re-running, don't just retry blind.

## 5. Rolling back manually (to a specific, older commit — not the immediately-previous one)

Every image `ci.yml` pushes is also tagged with the exact commit SHA it was built from, not only
`:latest`. To roll back further than `deploy.sh`'s own automatic one-step-back (§4) — a known-good
commit from further in the past:

```bash
# In .env:
CLAVARIS_IMAGE_TAG=<the git sha of the last known-good commit>

./deploy.sh
```

Set `CLAVARIS_IMAGE_TAG` back to blank (or `latest`) once the underlying issue is fixed and a new
commit is merged — pinning is a deliberate, temporary override, not the normal operating mode.

## 6. What this runbook does not cover, on purpose

- **Database backup/restore** — a real, distinct gap, tracked separately as `TD-FUT-006`. Do not
  treat this deployment as having a tested recovery path until that row closes.
- **Rotating a compromised credential on this host** — see the two existing incident-response
  runbooks (`incident-response-signing-key-compromise.md`,
  `incident-response-platform-client-compromise.md`) for the containment procedure itself; this
  runbook only covers routine, non-incident deploys.
- **Observability** — `docker-compose.observability.yml` (Prometheus/Alertmanager/Grafana/Zipkin)
  is a separate, optional compose file, not merged into `docker-compose.prod.yml` by default (a
  file never passed via `-f` is never parsed at all) — bring it up alongside this one explicitly
  if this host should page on the alert rules `infra/observability/alert-rules.yml` already
  defines.
