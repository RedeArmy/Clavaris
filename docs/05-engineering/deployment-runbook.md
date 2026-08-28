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
- A DNS `A` record for the domain this instance will be reachable at, already pointing at that
  public IP before the first `docker compose up` — Let's Encrypt's own HTTP-01 challenge (Caddy
  runs this automatically) fails if the domain doesn't resolve yet.
- Ports 80 and 443 reachable from the internet (security group / firewall rule) — Caddy needs 80
  for the ACME challenge and the automatic HTTP→HTTPS redirect, 443 for everything else.
- A GitHub Container Registry image to pull: `ci.yml`'s own `docker-build` job pushes
  `ghcr.io/<owner>/clavaris:latest` (and `:<git-sha>`) automatically on every push to `master` — no
  separate publish step to run by hand.

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
