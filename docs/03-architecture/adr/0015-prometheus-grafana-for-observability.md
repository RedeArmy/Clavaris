# ADR-0015: Self-hosted Prometheus + Alertmanager + Grafana for observability, not a SaaS or a unified all-in-one

**Status:** ✅ Aprobado (2026-08-24) — **implemented and live-verified 2026-08-24** (scope expanded same day to include full per-request tracing — see the addendum at the end of Decision and Consequences below; this is no longer decision-only).

## Context

`technical-debt-register.md` TD-FUT-011 (widened 2026-08-24) names a real, now-costly gap: this codebase has shipped several P1-grade security invariants that exist only as an individual structured-JSON log line nobody is watching in real time —

- `event=rate_limit_fail_open` (TD-SEC-022) — a live Redis outage, happening right now
- `event=refresh_token_reuse_detected` (BR-ID-03) — the highest-severity signal that codebase has: a stolen refresh token in active use
- `event=login_failure` / `event=platform_login_failure` — a credential-stuffing attempt in progress

`nfr-quality-attributes.md` §5 already anticipated this exact gap back on 2026-08-20 ("Full detail deferred to `docs/05-engineering/` once the observability stack is chosen, expected to mirror JobSeeker's Actuator/Micrometer/Prometheus/Grafana choice, not yet formalized as its own ADR here") — this ADR is that formalization, not a novel direction. This project's own "same engineering discipline as JobSeeker" framing applies directly: JobSeeker (this project's own origin, per ADR-0001) already runs this exact stack in production, so choosing it here is reusing a proven pattern, not evaluating cold.

The scope this ADR actually covers is narrower than "observability" as a category suggests: turning ~4 named structured log events into alertable metrics, plus a basic dashboard. Not distributed tracing — Clavaris is a modular monolith, one `app` deployable, so there is no cross-service request to trace yet.

`spring-boot-starter-actuator` is already a dependency (`app/pom.xml`), already exposing `/actuator/health/{liveness,readiness}` (`application.yml`'s own `management.endpoint.health.group.readiness.include: readinessState,db,redis` — the fix for the "Spring Boot readiness default gotcha" already live-tested and documented) — Micrometer's core is already on the classpath transitively through it. The gap is a metrics *registry/backend* and an *alerting* path, not Micrometer itself.

Three real options evaluated on their own merits:

- **Micrometer + self-hosted Prometheus + Alertmanager + Grafana.** The de facto standard pairing for a Spring Boot application's own metrics — `micrometer-registry-prometheus` is a first-class Micrometer registry (one dependency, exposes `/actuator/prometheus`), not a bolted-on integration. Matches the exact operational pattern ADR-0014 already established for Infisical: one or a few more Docker Compose services, self-hosted, no new vendor relationship, no telemetry leaving this project's own infrastructure. Against that: three moving parts (Prometheus scrapes, Alertmanager routes, Grafana renders), each with its own config file, more honestly "a real stack to operate" than a single `docker compose up` flag — the same honest tradeoff already accepted for Infisical.
- **A unified self-hosted alternative (SigNoz, OpenObserve).** Metrics + logs (+ traces, unneeded here) in one service instead of three — genuinely less to operate. Rejected: meaningfully younger and with far less track record than Prometheus/Grafana, which has been this ecosystem's de facto standard for the better part of a decade, and Micrometer's own Prometheus registry is the best-supported, first-class target — these newer tools would mean a less-proven integration path for less operational savings than it first appears.
- **A SaaS (Grafana Cloud free tier, Datadog free tier, etc.).** Zero operational burden — the one real advantage. Rejected on two grounds: (1) two of the four named signals this ADR exists to close (`rate_limit_fail_open`, `refresh_token_reuse_detected`) are security telemetry, and routing them to a third party *before* the mandatory external security review adds a new data-egress surface that review would then also have to cover; (2) this is the same "own it, don't outsource the control" reasoning ADR-0001 already locked in for the identity engine itself and ADR-0014 already applied to secrets — a third-party observability vendor is a new trust boundary this project has consistently avoided elsewhere without a correspondingly strong reason to make an exception here.

## Decision

**Self-hosted Prometheus + Alertmanager + Grafana**, fed by Micrometer's existing (soon-to-be-added `micrometer-registry-prometheus`) `/actuator/prometheus` endpoint — not a SaaS, not a unified all-in-one tool.

- New Micrometer `Counter`/`Gauge` instrumentation at the four call sites TD-FUT-011 names (`AntiAbuseRateLimitingFilter`'s fail-open path, `RotateRefreshTokenService`'s reuse-detection branch, `AuthenticateWithPasswordUseCase`/`AuthenticatePlatformAccountWithPasswordService`'s failure branches), alongside the structured log lines already there, not replacing them.
- New Prometheus/Alertmanager/Grafana services in their own optional Compose file (`docker-compose.observability.yml`), the same separate-file pattern `docker-compose.infisical.yml` already established and for the identical reason: Compose validates every `${VAR:?...}` required-variable check for every service in a file regardless of profile activation, so a genuinely separate file — never parsed unless passed via `-f` — is what keeps the default `docker compose up` flow untouched, not a `profiles:` block inside `docker-compose.yml` itself.
- Alert rules for the two P1-grade events (`rate_limit_fail_open`, `refresh_token_reuse_detected`) at minimum; `login_failure`/`platform_login_failure` get a rate-based threshold rule, not a fire-on-any-occurrence one, since some rate of ordinary failed logins is expected traffic, not a signal on its own.

**Addendum, 2026-08-24 — scope expanded to full per-request tracing, on explicit request.** This
ADR's own Context originally scoped out distributed tracing ("Clavaris is a modular monolith, one
`app` deployable, so there is no cross-service request to trace yet") — correct as far as it went,
but a real per-*request* trace (every filter/handler a single HTTP request passes through inside
that one process) is still genuinely useful and was explicitly requested, so it's in scope now:
Micrometer Tracing + Brave (`spring-boot-starter-zipkin` — the actual Spring Boot 4.1 starter, not
the bare `micrometer-tracing-bridge-brave`/`zipkin-reporter-brave` library jars alone, which compile
fine but ship zero Spring Boot autoconfiguration in this version — confirmed live the hard way, see
`app/pom.xml`'s own comment on that dependency), 100% sampling (`management.tracing.sampling.
probability: 1.0`, defensible only at v1's actual traffic volume), exported to a self-hosted Zipkin
(`docker-compose.observability.yml`). `traceId`/`spanId` now appear as flat fields in every
structured log line emitted during a traced request (Brave's `MDCScopeDecorator`, confirmed live
against a real running instance's own JSON log output) — a log line and a Zipkin trace for the same
request are now directly correlatable by that shared id.

## Consequences

- **Positive:** closes the gap this ADR exists for — the four named security-relevant events become things a human is actually paged for, not log lines that only matter in hindsight during an incident review.
- **Positive:** zero new vendor/trust boundary, consistent with every prior infrastructure decision in this project (ADR-0001, ADR-0014) — the metrics never leave infrastructure this project already operates.
- **Positive:** mirrors JobSeeker's own already-running choice (`nfr-quality-attributes.md` §5) — proven at that project's own scale already, not a cold evaluation.
- **Negative:** three more services to operate (Prometheus, Alertmanager, Grafana), each with its own config surface — real, ongoing operational cost for a solo developer, same class of tradeoff already accepted for Infisical.
- ~~**Deliberately deferred, not part of this decision**~~ — **implemented same day, 2026-08-24, see `technical-debt-register.md` TD-FUT-011's own closure note for the full build-and-verify record**: real Micrometer instrumentation at every named call site (plus token issuance/revocation, rate-limit allow/block decisions — a superset of what this ADR's own Decision section named as the minimum), `docker-compose.observability.yml`, real Alertmanager rule thresholds with `for:`/`severity` tuned per event, and (per the addendum above) full per-request tracing. Every layer of this — metric → Prometheus scrape → alert rule → Alertmanager → real SMTP → a real inbox, and a full request trace with real per-filter timing in Zipkin — was verified against a real running `docker compose` stack, not just started and assumed working; two real bugs were caught and fixed in the process (a Grafana bind-mount conflict; two wrong property names for the Zipkin endpoint, one a genuinely nonexistent property, both confirmed via the actual resolved jars' own `spring-configuration-metadata.json`, not guessed from Boot 3.x-era memory).
- **Negative:** like every ADR, revisiting this (e.g., moving to a managed open-source provider once real operational maturity or budget exists) requires an explicit new ADR per this project's own ADR conventions, not a quiet swap.

## Alternatives considered

See "Context" above — a unified self-hosted tool (SigNoz/OpenObserve) and SaaS options (Grafana Cloud, Datadog) were both evaluated on their actual merits before deciding, not assumed away.
