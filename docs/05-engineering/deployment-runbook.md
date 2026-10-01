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

**`GOOGLE_OAUTH_CLIENT_ID`/`SECRET`, `GITHUB_OAUTH_CLIENT_ID`/`SECRET` — live-found gap, 2026-09-16:**
`docker-compose.prod.yml`'s own comment says any non-blank placeholder satisfies its `:?` check if
this deployment won't enable social login yet — true for startup, but a real "Sign in with Google"
attempt against a placeholder value fails at Google's own consent screen with `Error 401:
invalid_client` / "The OAuth client was not found," not anywhere in this app's own logs. Confirmed
live: exactly this error, against exactly a leftover placeholder. To make social login actually
work on a given instance (ADR-0020 Decision 4 — one shared app per provider, every Organization's
button goes through the same pair):
- Google: [console.cloud.google.com](https://console.cloud.google.com) → APIs & Services →
  Credentials → Create Credentials → OAuth client ID → Application type: Web application.
  Authorized redirect URI must be exactly `{CLAVARIS_BASE_URL}/login/oauth2/code/google` (Spring
  Security's own default callback path — no override configured anywhere in this codebase).
- GitHub: [github.com/settings/developers](https://github.com/settings/developers) → OAuth Apps →
  New OAuth App. Authorization callback URL must be exactly
  `{CLAVARIS_BASE_URL}/login/oauth2/code/github`.
- Set the resulting four real values in `/opt/clavaris/.env`, then re-run `./deploy.sh` — these are
  read once at container startup, not hot-reloaded.

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

## 6. Backup and disaster recovery (TD-FUT-006)

`nfr-quality-attributes.md` §7 named "how do we recover if the primary database is lost" as a real,
unaddressed gap for a ≥99.5%-availability credential store — this section, plus
`scripts/host/backup-postgres.sh`/`restore-postgres.sh`, is that answer.

### 6a. What gets backed up, and why both halves matter together

Every backup captures **two** things, not just Postgres:

1. **The Postgres database** (`pg_dump -F c`, custom format — supports `pg_restore --clean
   --if-exists` and parallel restore, safer and faster than a plain `.sql` dump piped through
   `psql`).
2. **The `clavaris-signing-keys-data` volume** — the actual PKCS12 signing-key material
   `data-model.md` §2 confirms is never in the database, only referenced by `kid`/metadata from the
   `signing_keys`/`platform_signing_keys` tables. Restoring Postgres alone would bring back
   `SigningKey` rows pointing at key material that no longer exists on disk — a worse, harder-to-
   diagnose failure than no backup at all, since the app starts and looks healthy right up until the
   first token needs signing under a `kid` the restored keystore doesn't have.

### 6b. Scheduling backups

As the `clavaris` user, on the production host:

```bash
crontab -e
# Daily at 02:00 (this host's own low-traffic window, same convention as routine deploys):
0 2 * * * cd /opt/clavaris && ./backup-postgres.sh >> backups/backup.log 2>&1
```

Backups land in `/opt/clavaris/backups/`, retained `CLAVARIS_BACKUP_RETENTION_DAYS` days (default
14, set in `.env` to override) — old backups are pruned automatically on every run, not left to
grow unbounded. Copying `backups/` to storage off this VM (a second host, object storage) is a
real, separate, not-yet-automated step — a backup that lives only on the same disk as what it's
backing up doesn't survive that disk failing, which is worth naming plainly rather than implying
this alone is a complete off-site DR story.

### 6c. Restoring

```bash
cd /opt/clavaris
./restore-postgres.sh backups/clavaris-postgres-<timestamp>.dump backups/clavaris-signing-keys-<timestamp>.tar.gz
```

Destructive and confirmed interactively by default (`-y` skips the prompt, for scripted/rehearsed
use only) — stops `app`, replaces the signing-keys volume's contents and the database's contents
from the given backup pair, then restarts `app`. Confirm recovery with the same health check
`deploy.sh` itself uses: `docker compose -f docker-compose.prod.yml exec -T app curl -fsS
http://localhost:8080/actuator/health/readiness`.

### 6d. Live-verified RPO/RTO — what was actually measured, and its honest scope

Run end to end against a real `postgres:16` container and a real named Docker volume (this
runbook's own compose service names, not a mock) — seeded with representative rows (an
`organizations`/`accounts` pair with a real foreign key, and a real file written into the
signing-keys volume), then genuinely destroyed (`DROP TABLE`, the signing-key file deleted) before
restoring, not restored-onto-itself:

- **Backup**: ~1.7s for the seeded dataset.
- **Restore**: ~14s end to end (stop `app`, restore both volumes, restart `app`).
- **Data integrity**: the restored database returned the exact seeded row via a real join query;
  the restored signing-keys volume's file content was byte-identical to what was seeded.

**Honest scope note, not overclaimed**: this proves the backup/restore *mechanism* is correct
(dump format, `--clean --if-exists` restore semantics, the volume tar/untar round trip, the
stop-app/restore/restart-app sequence) against a real Postgres 16 engine — it does not itself prove
the numbers above at real production data volume, which this project has none of yet (zero real
consumer traffic, per `roadmap-and-release-plan.md` §14). `pg_restore`'s own runtime scales with
data size, so RTO at real scale should be re-measured once real data volume exists, not assumed to
stay at ~14s — this is the same "found live, not assumed" discipline this project applies
everywhere else, applied here to itself. **A real bug was caught during this exact verification
run, not hypothesized**: Docker Compose prefixes every named volume with its own project name
(derived from the deploy directory's basename) unless a volume declares an explicit `name:` —
`docker-compose.prod.yml` didn't, which would have made `backup-postgres.sh`'s hardcoded volume
name silently miss the real one in production. Fixed by adding explicit `name:` to every volume in
that file, confirmed safe to do now (before any real production instance holds real data) rather
than a live migration hazard.

## 7. Pre-production environment (VirtualBox VM) and automatic CD (ADR-0018 addendum, 2026-09-09)

Everything in §1–§7 above describes the single-VM artifact itself, deployable to any host with a
public IP. This section is specifically about **pre-production**: a real rehearsal of that exact
artifact — same `docker-compose.prod.yml`, same Caddy, same `.env` shape — on a VirtualBox VM on a
home network, before ever touching the real production host (Oracle Cloud's Always Free tier,
Ampere A1 shape). The goal is to catch real deployment problems (a missing `.env` value, a
firewall rule, a health-check timing issue) against the actual artifact, not a hand-wavier "it
should work" read of this document.

### 7a. VM specification

| | Pre-production (VirtualBox, as actually built) | Production target (Oracle Always Free, Ampere A1) |
|---|---|---|
| vCPU | 2 | 2 OCPU |
| RAM | 6 GB | 12 GB |
| Disk | 40 GB | up to 200 GB (boot + block) |
| OS | Ubuntu Server 26.04.1 LTS | Ubuntu (same) |

**Named gap, not silently assumed away**: §2a's own capacity-tuning table above sizes
`TOMCAT_MAX_THREADS`/`DB_HIKARI_MAX_POOL_SIZE` against a **3-vCPU** reference machine
(TD-PERF-007) — Oracle's Always Free Ampere A1 tier, in its single most powerful configuration,
gives only **2 OCPU total**, below that reference point, and this pre-production VM matches that
same 2-vCPU ceiling deliberately (rehearsing the real target's CPU shape, not just its RAM).
Confirmed against Oracle's own current published Always Free specs, not assumed from memory
(Oracle's own numbers have changed over time and third-party summaries disagree).

**Retuned values for this 2-vCPU host** (both environments — this VM and the eventual Oracle
host share the same vCPU count, so the same retuned values apply to both, not just one):

| Setting | §2a's 3-vCPU default | 2-vCPU value | Reasoning |
|---|---|---|---|
| `TOMCAT_MAX_THREADS` | 50 | **34** | Scaled proportionally to real core count (50 × 2/3 ≈ 33.3, rounded up) — Argon2id verification is CPU-bound, not just memory-bound, so keeping the 3-vCPU thread count on 2 real cores would only grow the queue behind the CPU's actual parallel capacity, not real throughput. |
| `DB_HIKARI_MAX_POOL_SIZE` | 10 | **5** | HikariCP's own `((core_count * 2) + spindle_count)` guidance applied literally to 2 cores: (2 × 2) + 1 = 5. |
| `DB_HIKARI_CONNECTION_TIMEOUT_MS` | 10000 | **10000, unchanged** | Not core-count-dependent — this bounds how long a request waits for a pool connection, which doesn't scale with vCPU count the way thread/pool *sizes* do. |
| `JAVA_OPTS` (`-Xmx`) | `1536m` | **`-Xmx1024m -Xms512m`** | Worst-case concurrent Argon2 memory at 34 threads: 34 × ~19MiB ≈ 646MiB — 1024m leaves comfortable heap/GC headroom above that without reserving memory this smaller host doesn't have much of to spare. |
| `mem_limit` (`app` service) | `2g` | **`2g`, unchanged** | This VM's real 6GB (more than the 4GB originally assumed) leaves enough headroom for Postgres/Redis/Caddy alongside a 2g `app` container without shrinking it — only the *JVM's own* heap ceiling needed to come down with the lower thread count, not the container's outer memory budget. |

Set the three Spring-consumed values (`TOMCAT_MAX_THREADS`, `DB_HIKARI_MAX_POOL_SIZE`,
`DB_HIKARI_CONNECTION_TIMEOUT_MS`) directly in `docker-compose.prod.yml`'s own `app.environment`
block, not `.env` alone — §2a above already explains why a blank `.env` value for these three
doesn't do what it looks like it should. `JAVA_OPTS` is the one exception that *is* safe to set in
`.env` alone (same section's own explanation).

VirtualBox network mode: **Bridged**, not NAT — this VM needs genuine outbound internet access for
both ngrok (§8b) and the self-hosted Actions runner (§8c), neither of which need any *inbound*
port opened on the host's own router. NAT with manual port-forwarding works too if bridged isn't
available on the network, but adds a step bridged doesn't need.

### 7b. Exposing the app to the internet — not deploying to it

Something has to make the running app reachable from the internet for real end-to-end testing (an
OAuth provider's own redirect callback, a webhook delivery target, JobSeeker's own dev environment
reaching this instance) — this is deliberately **not** part of how a new build gets onto this host
(see §8c for that). Two tools have filled this role; either is fine, but pick one per host and be
explicit about it, since — as found live, §7b addendum below — they need different Caddy config on
this end.

**ngrok** (the original choice here): install it on the VM itself (`snap install ngrok` or the
tarball from ngrok's own downloads page), authenticate with `ngrok config add-authtoken <token>`,
then `ngrok http 80`.

**Tailscale Funnel** (this pre-production host's own current choice, replacing ngrok): once
Tailscale itself is installed and the node is in the tailnet, `sudo tailscale funnel 80` exposes
this host's own port 80 at `https://<node-name>.<tailnet-name>.ts.net`, with Tailscale's own edge
terminating TLS — no ngrok account, no separate tunnel process to keep alive. Check what's
currently configured with `sudo tailscale funnel status`.

**Known, deliberate divergence from real production, named here rather than discovered later**: in
real production (a real domain's DNS `A` record pointing at Oracle's own public IP), Caddy performs
its own Let's Encrypt HTTP-01 challenge and terminates TLS itself (ADR-0018 Decision 1). Behind
either tool above, the tool's own edge terminates TLS instead — Caddy in this pre-production
rehearsal serves plain HTTP behind the tunnel, not real Let's Encrypt-issued TLS. This is an
accepted gap for rehearsing everything else (compose file, health checks, migrations, `.env` shape,
the deploy/rollback mechanism itself) — it does not rehearse Caddy's own ACME flow. Closing that gap
too would need a raw TCP tunnel to port 80/443 reaching Caddy unmodified (ngrok's paid reserved-
domain tier, or Tailscale Serve/Funnel's own TCP-forward mode instead of HTTP) — not done by default
here; revisit if rehearsing the ACME flow itself becomes worth the added setup before the real
Oracle cutover.

**Live-found, 2026-09-16 — Tailscale Funnel and Caddy both want port 443, and only one can have
it:** `docker-compose.prod.yml`'s own `caddy` service publishes both 80 and 443, assuming Caddy
itself owns TLS termination (production's own real shape) — it was never actually adjusted for
"something else terminates TLS in front of it" the way the paragraph above already described in
words. The first time Tailscale Funnel and this compose file's own `caddy` both tried to bind 443
on the same host, `docker compose up` failed outright: `failed to set up container networking:
driver failed programming external connectivity` — Funnel already held the port (`sudo ss -tlnp |
grep :443` confirmed `tailscaled`, not a stale container, was the actual holder).

Fixed with an **additive override file, not an edit to `docker-compose.prod.yml` itself** — that
file must stay production's own real shape (its own domain, Caddy owns 443 directly) for the day
production actually deploys:

- `docker-compose.preprod.yml` — redeclares `caddy`'s own `ports` (`!override`, just `80:80` — see
  that file's own comment for why the Compose Specification's plain merge behavior on a list-valued
  property isn't enough here, confirmed live via `docker compose ... config`, not assumed) and
  `volumes` (mounts `Caddyfile.preprod` in place of the base file's own `Caddyfile` — Compose
  merges volumes by target path, so this one entry is enough on its own).
- `Caddyfile.preprod` — plain `:80` site address (no domain, no automatic HTTPS attempt) reverse-
  proxying to `app:8080`, otherwise identical to the base `Caddyfile`.

`scripts/host/deploy.sh` includes this override automatically whenever the file is present next to
it — nothing to pass on the command line, and a host that never has this file (production's own)
behaves exactly as before this addendum. On this pre-production host specifically, both new files
need fetching once, the same way every other file here does (§3's own "these three files aren't
git-cloned" posture, now four):

```bash
cd /opt/clavaris
curl -fsSL https://raw.githubusercontent.com/RedeArmy/Clavaris/master/docker-compose.preprod.yml -o docker-compose.preprod.yml
curl -fsSL https://raw.githubusercontent.com/RedeArmy/Clavaris/master/Caddyfile.preprod -o Caddyfile.preprod
./deploy.sh
```

### 7c. Automatic deployment — self-hosted GitHub Actions runner

`ci.yml`'s own `deploy-preprod` job (`needs: ci-passed`, gated to `push` on `master` only) runs
`deploy.sh` (§4 above) automatically on every merge — no one needs to SSH in and run it by hand.
This works without opening any inbound port on the VM's own router/firewall: a **self-hosted GitHub
Actions runner**, installed as a service directly on this VM, polls GitHub over an outbound HTTPS
connection for work — the same direction ngrok's own tunnel and every other outbound connection
this VM makes already goes, never inbound.

**Why a self-hosted runner instead of a GitHub-hosted runner SSHing in**: this VM has no public IP
of its own (it's behind a home router/NAT) — a GitHub-hosted runner has no address to SSH to at
all without something bridging that gap, and the obvious bridge (tunneling SSH through ngrok) needs
a *stable* address, which ngrok's free tier doesn't give (the address changes every time the tunnel
restarts) — a paid reserved TCP address would fix that, but at that point a self-hosted runner is
simpler, needs no SSH key material stored in GitHub Secrets at all, and is the pattern GitHub's own
docs recommend specifically for exactly this shape of problem (a private/NAT'd deployment target).

Setup, once per host (see GitHub's own `Settings → Actions → Runners → New self-hosted runner` for
the exact, account-specific download/token commands — not reproduced here since the registration
token is single-use and account-specific):

```bash
# As the 'clavaris' user (the same deploy user bootstrap.sh already created):
su - clavaris
mkdir actions-runner && cd actions-runner
# ... download + extract the runner tarball GitHub's own UI gives you ...
./config.sh --url https://github.com/RedeArmy/Clavaris --token <token-from-github-ui> --labels preprod
```

Then, as root, install it as a systemd service so it survives reboots and doesn't depend on an
open terminal session:

```bash
cd /home/clavaris/actions-runner
./svc.sh install clavaris
./svc.sh start
```

The `preprod` label (not bare `self-hosted`) is deliberate: the day a second self-hosted runner is
registered on the real Oracle production host, it gets its own `production` label instead, and
`ci.yml`'s own `deploy-preprod` job (`runs-on: [self-hosted, preprod]`) can never accidentally pick
up production's runner or vice versa — two independent deploy targets, never conflated by an
ambiguous shared label. A future production deploy job should very likely gate on a GitHub
Environment with a required reviewer (unlike `pre-production`'s own unattended-by-design flow here)
— not added now because that host doesn't exist yet, but the `environment:` block already in
`ci.yml`'s `deploy-preprod` job is exactly where that protection rule would attach for its own
`production` counterpart, with zero job-logic changes needed to add it later.

## 8. What this runbook does not cover, on purpose

- **Off-site/geo-redundant backup copies** — §6b above is honest that this is not yet automated;
  backups currently live on the same disk as the data they back up.
- **Rotating a compromised credential on this host** — see the two existing incident-response
  runbooks (`incident-response-signing-key-compromise.md`,
  `incident-response-platform-client-compromise.md`) for the containment procedure itself; this
  runbook only covers routine, non-incident deploys.
- **Observability** — `docker-compose.observability.yml` (Prometheus/Alertmanager/Grafana/Zipkin)
  is a separate, optional compose file, not merged into `docker-compose.prod.yml` by default (a
  file never passed via `-f` is never parsed at all) — bring it up alongside this one explicitly
  if this host should page on the alert rules `infra/observability/alert-rules.yml` already
  defines.

## 9. Per-client custom domains for embedded/branded login (ADR-0009, TD-FUT-028)

`ADR-0009` is the "why" (embedded-modal login needs a same-site cookie, which needs a custom
domain scoped to the consumer's own registrable domain); this section is the "how" — the one real
gap that ADR's own "Open questions" section and `technical-debt-register.md` TD-FUT-028 both named:
nothing previously explained what an operator actually runs. Everything referenced below
(`ClientBranding`, `ClientDomainConfig`, `CustomDomainRequestRewriteFilter`, the CSP
`frame-ancestors` relaxation, the modal-aware session cookie) is already real, shipped, tested
application code — this section only documents how to drive it.

### 9a. When this applies

A `SHARED`-mode client (today's default — no custom domain at all) is fine for the plain,
full-page-redirect hosted login with zero setup. This section only matters for a production
`OAuthClient` that wants the embedded-iframe-modal experience (`display=modal`) — BR-CLIENT-04
requires a verified custom domain for that specific experience, not for ordinary redirect-based
login.

### 9b. Set branding (optional, usually done alongside the domain)

```bash
curl -X PUT "{CLAVARIS_BASE_URL}/api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/branding" \
  -H "Authorization: Bearer {platform-client-access-token}" \
  -H "Content-Type: application/json" \
  -d '{"logoUrl": "https://example.com/logo.svg", "primaryColor": "#1A73E8", "applicationDisplayName": "Example Inc."}'
```

Same `client_credentials`-gated management API every other admin action here already uses — see
`SetClientBrandingController.java` (`client-registry-module`). `logoUrl` must be an absolute
`https` URL, `primaryColor` a `#RGB`/`#RRGGBB` hex value, `applicationDisplayName` non-blank and
≤100 characters — a `400` means one of the three failed that validation.

### 9c. Request the domain (`CNAME` or `PROXY`)

```bash
curl -X PUT "{CLAVARIS_BASE_URL}/api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/domain-config" \
  -H "Authorization: Bearer {platform-client-access-token}" \
  -H "Content-Type: application/json" \
  -d '{"mode": "CNAME", "hostname": "login.example.com", "embeddingOrigin": "https://example.com"}'
```

(`RequestClientDomainConfigController.java`.) `embeddingOrigin` is the consumer's own top-level
page origin that will embed the login iframe — deliberately separate from `redirectUris`, since the
embedding page isn't necessarily one of the client's own OAuth2 callback URLs. A `409` means
`hostname` is already claimed by a different `OAuthClient` (real cross-tenant conflict, not a bug —
ownership verification exists specifically to prevent this). The response carries a
`dnsTxtChallengeToken` — copy its exact value for the next step; it's never a secret (knowing it
grants no capability beyond what publishing a DNS record for a domain you already control already
requires).

Publish a DNS `TXT` record:

```
_clavaris-challenge.login.example.com.   TXT   "<dnsTxtChallengeToken from the response above>"
```

(The `_clavaris-challenge.` prefix is `VerifyClientDomainOwnershipService`'s own
`CHALLENGE_PREFIX` constant — this exact value, not an approximation.)

### 9d. Verify ownership

```bash
curl -X POST "{CLAVARIS_BASE_URL}/api/v1/admin/organizations/{organizationId}/clients/{oauthClientId}/domain-config:verify-ownership" \
  -H "Authorization: Bearer {platform-client-access-token}"
```

Admin-triggered, not a background poller — same "manually-triggered, audited operation" posture
CLAUDE.md §6 already establishes for signing-key rotation. A `200` with
`"verificationStatus": "FAILED"` is a normal, retryable outcome (DNS hasn't propagated yet, a
typo'd record) — fix/wait, then call this again; it is not an error response. Only a `VERIFIED`
`ClientDomainConfig` is ever eligible for the CSP `frame-ancestors` relaxation (§9g) or custom-domain
routing (`CustomDomainRequestRewriteFilter`).

### 9e. `CNAME` mode — standing up TLS (the actual gap this section closes)

Clavaris never issues or terminates TLS itself for a custom domain (ADR-0009 §2, a confirmed
scoping decision, not an oversight) — an operator stands up TLS termination in front of it. Two
options, both reusing this runbook's own existing Caddy choice (§1, ADR-0018) rather than
introducing a second tool:

**Option 1 — a second Caddy site block, same container (recommended at today's scale).** Add to
the production `Caddyfile`, alongside the existing `{$CLAVARIS_DOMAIN}` block:

```caddyfile
login.example.com {
    reverse_proxy app:8080
    header Strict-Transport-Security "max-age=31536000; includeSubDomains"
}
```

This mirrors the primary domain's own block exactly — Caddy requests and renews its own Let's
Encrypt certificate for this hostname automatically, same zero-manual-cert-management property §1
already cites for `{$CLAVARIS_DOMAIN}`. Before adding this block, confirm the consumer's own DNS
`CNAME` record (`login.example.com → {CLAVARIS_DOMAIN}`) already resolves — exactly the same
prerequisite §2 already states for the primary domain's own ACME HTTP-01 challenge, now repeated
per custom domain. Apply with `docker compose restart caddy` (or an in-place reload) — this is a
real, named manual step per new client domain, not automated; acceptable at the "single-digit
consuming applications" scale `nfr-quality-attributes.md` §3 already scopes v1 to.

**Option 2 — a separate reverse proxy or managed certificate** (Traefik, nginx+certbot, a cloud
load balancer with managed TLS) — equally valid per ADR-0009 §2's own wording. Forward to
`app:8080` over the same Docker network (or a reachable internal address, if that proxy runs
outside this compose stack) — the operator's own tool choice; this runbook doesn't walk through
every option, only the Caddy path this deployment actually runs.

**Named future improvement, not built in this pass**: Caddy's own `on_demand_tls` feature (dynamic
per-domain certificates gated by an "ask" callback) would remove the manual Caddyfile-edit step per
client. Not done here because it needs a new, small Clavaris-side verification endpoint for Caddy's
`ask` directive to call — real new code, not documentation — worth revisiting if the number of
custom domains ever grows past "a Caddyfile edit per client" being reasonable.

### 9f. `PROXY` mode

The consumer runs their own reverse proxy on their own infrastructure — their TLS, their tool
choice, their job to keep the certificate renewed. Clavaris-side steps are identical to `CNAME`
(§9c/§9d, just `"mode": "PROXY"`) — the DNS TXT ownership-verification model is shared between both
modes (ADR-0009 §2's own resolved "Open questions"). The one real difference: the consumer's proxy
forwards to Clavaris's own real, public base URL (`{CLAVARIS_BASE_URL}` — the production domain
this same runbook's §1-§8 already stood up), not to an internal `app:8080` address they have no
network path to. Representative forwarding config (nginx):

```nginx
server {
    listen 443 ssl;
    server_name login.example.com;
    # ... the consumer's own certificate/key directives ...
    location / {
        proxy_pass https://{CLAVARIS_BASE_URL};
        proxy_set_header Host $host;
    }
}
```

### 9g. Confirming it actually works

- `curl -H "Host: login.example.com" https://{server-ip}/o/{organizationId}/login` (or the real DNS
  name, once it resolves) should route to that Organization's own login page —
  `CustomDomainRequestRewriteFilter`'s internal forward, not a 404.
- Open `https://login.example.com/o/{organizationId}/login?display=modal&client_id={clientId}` in a
  browser, check the response's own `Content-Security-Policy` header in devtools' Network tab —
  `frame-ancestors` should now name the registered `embeddingOrigin`, not `'none'`. This only
  activates for a `VERIFIED` `ClientDomainConfig` with a real `embeddingOrigin` set
  (`EmbeddingEligibilityChecker`) — a `DEVELOPMENT`-tier Organization's client gets a wildcard
  (`*`) instead, with a warning logged, a deliberate testing convenience never used in production.
- Confirm the session cookie's own `Set-Cookie` header shows `SameSite=None; Secure` on this same
  flow (`ModalAwareSessionCookieSerializer`) — `SameSite=Lax` here means the embedded/iframe flow
  will silently drop the session on its first cross-site navigation back to the parent page.

### 9h. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `verificationStatus: "FAILED"` after §9d | DNS TXT record not yet propagated, or published with the wrong value | Re-check the exact `TXT` record value against the `dnsTxtChallengeToken` from §9c, wait for propagation, re-run §9d |
| `409` on §9c | `hostname` already claimed by a different `OAuthClient` (a different tenant, or a stale leftover from this same client under a different id) | Pick a different hostname, or resolve the conflicting claim first — this is ownership verification working as intended, not a bug |
| `frame-ancestors` still `'none'` after a `VERIFIED` domain | No `embeddingOrigin` was set on the domain-config request (§9c), or the client belongs to a different Organization than the one in the URL | Re-check the `embeddingOrigin` field on `GET .../domain-config`; confirm `organizationId` in the request matches the client's own owning Organization |
| TLS handshake fails on the custom domain | Caddy's own ACME challenge couldn't complete (DNS not pointed at this host yet, port 80 not reachable) | `docker compose logs caddy` — same pointer §1/§2 already give for the primary domain's own ACME flow |
