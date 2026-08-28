# ADR-0018: Production deployment tooling — reverse proxy, secrets posture, and IaC timing

**Status:** ✅ Aprobado (2026-08-28)

## Context

TD-FUT-013 (production deployment artifact) closed 2026-08-28 with a real single-VM
`docker-compose.prod.yml` + `Caddyfile` + GHCR image push (`technical-debt-register.md` §6). That
work made three tooling choices inline, without a dedicated comparison written down anywhere. This
ADR is that comparison, requested explicitly afterward — "easy and fast to deploy" was the one
constraint named for all three, and it turns out to point the same direction on all three: toward
fewer moving parts, not toward the most powerful available tool.

## Decision 1 — Reverse proxy / web server: Caddy, not nginx or Apache

| | **Caddy** | **nginx** | **Apache (httpd)** |
|---|---|---|---|
| Automatic TLS (Let's Encrypt) | Built in, zero extra tooling | Requires `certbot`/`acme.sh` — a separate cron job and a separate failure mode (renewal silently breaking) | Same certbot dependency as nginx |
| Config surface for this exact use case (proxy to one backend, TLS, one security header) | ~15 lines, one file | Verbose: separate `server`/`location`/`ssl_certificate` blocks, manual cipher-suite/HSTS/OCSP-stapling config — easy to get subtly wrong on a first attempt | Most verbose of the three (`mod_proxy`/`mod_ssl`), least common choice specifically for "reverse proxy in front of a containerized app" today |
| Raw throughput / memory at very high request volume | Slightly behind nginx (Go GC vs. nginx's C event loop) | Best of the three | Heaviest of the three |
| Relevance of that throughput gap at v1's actual scale | None — `nfr-quality-attributes.md` §3's own expected load is single-digit consuming applications, and the measured bottleneck (TD-FUT-017, Argon2id CPU cost inside the app itself) sits entirely below the proxy layer | — | — |
| Operational burden for a solo developer | Lowest — one binary, one config file, certs renew themselves | Real, ongoing — a second scheduled job to monitor, a real page-worthy incident class (expired cert) this project doesn't otherwise have | Same real burden as nginx, plus more config surface |
| Ecosystem/documentation depth | Smaller than nginx's, but sufficient for this exact, narrow use case (reverse proxy + TLS) | Largest of the three | Large, but skewed toward `mod_php`/legacy use cases irrelevant here |

**Decision: Caddy.** The one dimension that actually matters at this project's current scale —
operational simplicity for a solo developer, matching "easy and fast to deploy" directly — is
Caddy's clearest win, and it isn't close: automatic certificate issuance and renewal removes an
entire class of 3am incident (a silently-expired cert) that both alternatives would introduce as a
second system to babysit. nginx's raw-throughput advantage is real but irrelevant here — the
measured ceiling on this system (TD-FUT-017) is Argon2id CPU cost inside the JVM, not anything a
faster reverse proxy would move. Same "don't build/operate more than the problem needs" reasoning
ADR-0001 already applies to not hand-rolling the OAuth2 state machine, applied here to not
hand-rolling certificate lifecycle management.

## Decision 2 — Secrets/config for the single-VM artifact: `.env`, not mandatory Infisical

ADR-0014 already decided *which* secrets manager to build (self-hosted Infisical) and built it as
**dual-mode**, not a hard cutover — `docker-entrypoint.sh` falls back to plaintext env vars
unconditionally whenever `INFISICAL_CLIENT_ID`/`SECRET` are unset. This decision is narrower and
different: for *this specific* single-VM production artifact, should `.env` or Infisical be the
default, primary path?

- **Mandating Infisical here** would mean running `docker-compose.infisical.yml`'s own three extra
  services (`infisical-db`, `infisical-redis`, `infisical` itself) *on the same single VM* this
  artifact is deliberately scoped to keep simple (TD-FUT-013's own "deliberately not Kubernetes"
  reasoning) — roughly doubling the service count (two Postgres instances, two Redis instances, a
  full extra web application) to protect secrets that are already readable by nobody except root
  on that same VM. That's real, disproportionate operational weight for the actual threat this
  buys protection against, and directly works against "easy and fast" — the same over-building
  ADR-0014 itself already rejected when it ruled out Vault for being too heavy for a solo
  developer, now reapplied one layer down.
- **`.env`** is what every secret in this project already documents (`.env.example`), requires no
  additional service, and — with the file-permission hardening `scripts/host/bootstrap.sh` now
  applies automatically (`chmod 600`, owned by the deploy user only) — is a proportionate control
  for a single-operator, single-VM deployment. Full-disk encryption (offered by default or as a
  checkbox by essentially every VPS/cloud provider) is the compensating control for the
  "plaintext at rest" risk `.env` alone doesn't close.

**Decision: `.env` is the default path for this artifact.** Infisical stays exactly what ADR-0014
already built it as — genuinely optional, one env-var pair away from switching on — rather than
this ADR mandating it. The trigger to revisit: a second environment (staging), a second operator
who needs scoped access without sharing one file, or a compliance requirement (SOC 2's own
secret-rotation-with-audit-trail expectation, `ADR-0016`) that `.env` structurally can't satisfy.
None of those are true yet.

## Decision 3 — Terraform: not yet, deferred with a named trigger

Evaluated directly: does provisioning *one* VM, for *one* environment, benefit from Terraform's own
value proposition (reproducible, versioned, auditable infrastructure, painless multi-resource/
multi-environment coordination)?

- Terraform's cost here is real and immediate: a state file to store and protect (a remote backend,
  itself a new piece of infrastructure to secure, ironically for a project whose entire point this
  week was *reducing* moving parts), a new tool to learn and maintain, and a new failure mode
  (state drift between what Terraform believes exists and what's actually running) — for a single
  resource that, once created, essentially never changes shape.
- Terraform's payoff shows up precisely when this project reaches the situation TD-FUT-020 (multi-
  instance orchestration, deliberately deferred alongside this same decision) already names as its
  own trigger: multiple environments, multiple coordinated resources (VM + managed DB + DNS +
  firewall rules as one auditable unit), or a second operator who needs to reproduce infrastructure
  without tribal knowledge of "how the VM was actually set up."
- The middle ground that *is* worth doing now, and is done as part of this ADR: `scripts/host/
  bootstrap.sh` documents and automates the manual provisioning steps (Docker install, firewall
  rule for 80/443, deploy user, directory/permission setup) as a real, runnable script — not
  Terraform, but not tribal knowledge either. The DNS record and the VM/firewall creation itself
  stay a documented manual step (`deployment-runbook.md` §2) — the one part of this that's
  genuinely a 5-minute, rarely-repeated action on whatever provider is chosen, not yet worth
  automating.

**Decision: no Terraform yet.** Tracked as `TD-FUT-021` (`technical-debt-register.md` §3) with the
trigger condition above named explicitly, so "we decided against it" and "we forgot about it" stay
distinguishable the way every other deferred-by-decision row in this register already is.

## Consequences

- **Positive:** all three decisions point the same direction — fewer services, fewer files, fewer
  new failure modes — directly serving the one constraint that was named for all three
  ("easy and fast"), not three independent optimizations that happen to agree by coincidence.
- **Positive:** none of the three closes off the more powerful alternative later — Infisical is
  already built and one env-var pair away, Terraform's trigger is named rather than silently
  assumed, and nginx/Apache remain viable if Caddy's own tradeoffs ever change (unlikely at this
  project's scale, but not structurally foreclosed).
- **Negative:** `.env`-as-primary means secret rotation is still a manual, undocumented-until-now
  process on this specific artifact — worth a short runbook addition the day this is actually
  exercised for real, not before.
- **Negative:** Caddy's smaller ecosystem means a genuinely exotic proxy requirement (unlikely at
  this project's current scope) would have fewer existing recipes to draw from than nginx's.

## Alternatives considered

See Decisions 1–3 above — nginx, Apache, mandatory Infisical, and Terraform were each evaluated on
their actual merits for this specific artifact, not assumed away.
