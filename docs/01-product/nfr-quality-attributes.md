# Non-Functional Requirements / Quality Attributes — Clavaris

🟡 En revisión

## 1. Security (the dominant quality attribute for this system)

- Mandatory external security review before any consumer sends real user traffic — this gates production launch, full stop.
- OWASP ASVS Level 2 as the baseline control set to self-assess against ahead of the external review (informal checklist, not a certification).
- Signing keys (RS256) rotate with overlap; no key has an unbounded lifetime; JWKS always publishes the previous key until every token issued under it has naturally expired.
- Rate limiting on `/oauth2/token` and the login endpoint tuned specifically against credential stuffing (distinct thresholds from a generic API rate limit) from the first deployment.
- Full detail: `docs/04-security/threat-model-stride.md`, `docs/04-security/security-architecture.md`.

## 2. Availability

- Target ≥ 99.5% for the OIDC-facing endpoints (`/authorize`, `/token`, `/userinfo`, `/jwks.json`) — every consumer's login flow depends directly on these being up.
- The management API (organizations, client registry) can tolerate a lower bar in v1 (best-effort) — it is not on the hot path of an end user logging into a consumer application.
- No single point of failure at the data layer beyond what PostgreSQL/Redis already require for JobSeeker-scale infrastructure — this is not being over-engineered for a load level Clavaris doesn't have yet.

## 3. Performance

- Token issuance (`/oauth2/token`) p95 < 300ms under expected v1 load (single-digit consumers, not internet-scale) — generous relative to what Spring Authorization Server can do, deliberately not a target that forces premature optimization.
- `/jwks.json` and the discovery document are cacheable (standard HTTP caching headers) — consumers should rarely need to hit them live per request.
- **First real, measured data against the target above, 2026-08-24 (TD-TEST-004, `load-testing/README.md`)**: the raw HTTP/Postgres/Redis stack has real headroom (`/oauth2/jwks`, uncomplicated by password hashing: p95 56ms at 50 concurrent, p95 225ms at 100 concurrent, zero failures). `/oauth2/token` itself is a different story — Argon2id secret verification (ADR-0005, deliberately CPU/memory-hard) is the real bottleneck, not the database: p95 stayed under target only up to roughly single-digit concurrent requests on the 3-core machine this was measured on (p95 161ms at concurrency 1, already at the 300ms boundary by concurrency 3, 537ms at concurrency 10, a queueing-collapse 14.2s at concurrency 30). This is a real, load-bearing finding for capacity planning, not just a benchmark curiosity — tracked as **TD-FUT-017**, not silently left as a one-off number.

## 4. Portability / integration cost

- Any OIDC-conformant client library, in any language, must be able to complete the Authorization Code + PKCE flow against Clavaris without custom glue code — this is the measurable form of "reusable across any project" (vision-document §6).
- No proprietary claims required in the ID token beyond what standard OIDC scopes provide (`openid`, `profile`, `email`) plus whatever a consumer explicitly requests via custom scopes it registers for itself.

## 5. Observability

- Structured logs (JSON) for every authentication event (login success/failure, token issuance, token revocation, password reset requested/completed) — without ever logging the credential or token value itself (BR-DATA-01).
- Metrics: login success/failure rate, token issuance latency, refresh token reuse-detection triggers (a reuse detection firing is a security signal worth alerting on, not just logging).
- **Implemented and live-verified 2026-08-24 (ADR-0015, TD-FUT-011 closed)**: Micrometer + self-hosted Prometheus/Alertmanager/Grafana, mirroring JobSeeker's own already-running choice. Every named event (`rate_limit_fail_open`, `refresh_token_reuse_detected`, `login_failure`/`platform_login_failure`, plus token issuance/revocation and every rate-limit allow/block decision) is a real counter, scraped by Prometheus, with a real alert rule (`infra/observability/alert-rules.yml`) that fires into Alertmanager and sends a real email — verified end-to-end against a real running stack, including a real message landing in a real SMTP inbox (Mailpit in dev; Resend's own SMTP relay in production, `alertmanager.prod.yml.example`).
- **Full per-request traceability, added the same day on explicit request, expanding this ADR's original scope**: every HTTP request gets a real trace (Micrometer Tracing + Brave, 100% sampled, exported to a self-hosted Zipkin) with per-filter/per-layer timing, and `traceId`/`spanId` appear as real fields in every structured log line emitted during that request — confirmed live, not assumed, including two real property-name bugs caught and fixed in the process (see ADR-0015's own addendum).
- Real HTTP-per-endpoint latency percentiles (p50/p95/p99) now exist for every endpoint, including `/oauth2/token` — the number this document's own §3 target names — via `management.metrics.distribution.percentiles-histogram.http.server.requests`, with a dedicated `TokenIssuanceLatencyHigh` alert watching the 300ms target continuously, not just the one-time `load-testing/` measurement (TD-TEST-004/TD-FUT-017).

## 6. Maintainability

- Solo-developer operability is a hard constraint, not an aspiration — every NFR above is calibrated against "one person can reason about and operate this," which is why availability and performance targets are generous rather than hyperscale-grade (`project-charter.md` §5).

## 7. Known gaps not yet scoped (flagged, not decided)

Surfaced during a design review of the full system, none of these are committed scope for any release yet — listed here so they aren't silently lost before someone deliberately decides whether/when to schedule them:

- **Backup/disaster-recovery story for PostgreSQL** (accounts, signing keys' metadata, organizations) — not mentioned in any document reviewed. For a system with a ≥99.5% availability target that is the credential store for every consumer, "how do we recover if the primary database is lost" is a real gap, not an implementation detail to discover during an actual incident.
- **Graceful degradation when a social login provider (Google, GitHub) is down** — undecided whether login falls back to password-only or fails outright for accounts without a password credential. Worth a explicit BR before social login ships (adjacent to the existing open question in `prd-mvp.md` §5).
- ~~Health/readiness endpoints to actually operationalize the ≥99.5% target~~ — **closed and live-verified 2026-08-17**: the `app` bootstrap module wires Actuator's Kubernetes-style probe groups (`/actuator/health/liveness`, `/actuator/health/readiness`) from day one — see `docker-compose.yml` and `app/src/main/resources/application.yml`. Verified against a real `docker compose up` stack, not just config review: killing the `postgres` container correctly flipped `/actuator/health/readiness` to `503 DOWN` within Docker's own healthcheck window (5 consecutive failures at a 10s interval), and the app self-healed back to `200 UP` within one interval of Postgres coming back — no restart needed. **A real bug was caught and fixed in the process**: Spring Boot's `readiness` probe group only reflects `readinessState` (has the app finished starting?) by default, *not* downstream dependencies — the first build reported `200 UP` on `/readiness` while Postgres was down and `/actuator/health` itself correctly showed `db: DOWN`. Fixed via explicit `management.endpoint.health.group.readiness.include: readinessState,db,redis`. Flagging this pattern for whoever builds the next service on this stack: the default Actuator readiness group is not automatically meaningful, verify it against a real dependency failure, don't assume config review catches it — this one didn't, only running it did. What remains open is *acting* on these signals in a real deployment (alerting, auto-restart policy) — that's the on-call item below, not the endpoints themselves.
- **On-call / error-budget process** for a solo developer — §6 above states the constraint honestly, but "one person can operate this" isn't yet paired with what happens when that one person is asleep during an incident. The health endpoints above are the *mechanism* to observe this; the *process* around them (who gets paged, what an out-of-hours incident looks like for a team of one) is still unresolved, consistent with `project-charter.md` §7's similar unmodeled-operating-cost question.
