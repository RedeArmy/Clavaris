# Non-Functional Requirements / Quality Attributes — Clavaris

🟡 En revisión

## 1. Security (the dominant quality attribute for this system)

- Mandatory external security review before any consumer sends real user traffic (`CLAUDE.md` §6) — this gates production launch, full stop.
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

## 4. Portability / integration cost

- Any OIDC-conformant client library, in any language, must be able to complete the Authorization Code + PKCE flow against Clavaris without custom glue code — this is the measurable form of "reusable across any project" (vision-document §6).
- No proprietary claims required in the ID token beyond what standard OIDC scopes provide (`openid`, `profile`, `email`) plus whatever a consumer explicitly requests via custom scopes it registers for itself.

## 5. Observability

- Structured logs (JSON) for every authentication event (login success/failure, token issuance, token revocation, password reset requested/completed) — without ever logging the credential or token value itself (BR-DATA-01).
- Metrics: login success/failure rate, token issuance latency, refresh token reuse-detection triggers (a reuse detection firing is a security signal worth alerting on, not just logging).
- Full detail deferred to `docs/05-engineering/` once the observability stack is chosen (expected to mirror JobSeeker's Actuator/Micrometer/Prometheus/Grafana choice, not yet formalized as its own ADR here).

## 6. Maintainability

- Solo-developer operability is a hard constraint, not an aspiration — every NFR above is calibrated against "one person can reason about and operate this," which is why availability and performance targets are generous rather than hyperscale-grade (`project-charter.md` §5).

## 7. Known gaps not yet scoped (flagged, not decided)

Surfaced during a design review of the full system, none of these are committed scope for any release yet — listed here so they aren't silently lost before someone deliberately decides whether/when to schedule them:

- **Backup/disaster-recovery story for PostgreSQL** (accounts, signing keys' metadata, organizations) — not mentioned in any document reviewed. For a system with a ≥99.5% availability target that is the credential store for every consumer, "how do we recover if the primary database is lost" is a real gap, not an implementation detail to discover during an actual incident.
- **Graceful degradation when a social login provider (Google, GitHub) is down** — undecided whether login falls back to password-only or fails outright for accounts without a password credential. Worth a explicit BR before social login ships (adjacent to the existing open question in `prd-mvp.md` §5).
- ~~Health/readiness endpoints to actually operationalize the ≥99.5% target~~ — **closed and live-verified 2026-08-17**: the `app` bootstrap module wires Actuator's Kubernetes-style probe groups (`/actuator/health/liveness`, `/actuator/health/readiness`) from day one — see `docker-compose.yml` and `app/src/main/resources/application.yml`. Verified against a real `docker compose up` stack, not just config review: killing the `postgres` container correctly flipped `/actuator/health/readiness` to `503 DOWN` within Docker's own healthcheck window (5 consecutive failures at a 10s interval), and the app self-healed back to `200 UP` within one interval of Postgres coming back — no restart needed. **A real bug was caught and fixed in the process**: Spring Boot's `readiness` probe group only reflects `readinessState` (has the app finished starting?) by default, *not* downstream dependencies — the first build reported `200 UP` on `/readiness` while Postgres was down and `/actuator/health` itself correctly showed `db: DOWN`. Fixed via explicit `management.endpoint.health.group.readiness.include: readinessState,db,redis`. Flagging this pattern for whoever builds the next service on this stack: the default Actuator readiness group is not automatically meaningful, verify it against a real dependency failure, don't assume config review catches it — this one didn't, only running it did. What remains open is *acting* on these signals in a real deployment (alerting, auto-restart policy) — that's the on-call item below, not the endpoints themselves.
- **On-call / error-budget process** for a solo developer — §6 above states the constraint honestly, but "one person can operate this" isn't yet paired with what happens when that one person is asleep during an incident. The health endpoints above are the *mechanism* to observe this; the *process* around them (who gets paged, what an out-of-hours incident looks like for a team of one) is still unresolved, consistent with `project-charter.md` §7's similar unmodeled-operating-cost question.
