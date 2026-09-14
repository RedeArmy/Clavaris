# Market and Alternatives Research — Clavaris

🟡 En revisión

This is not commercial market research in the traditional sense — Clavaris is not (yet) a commercial product (`vision-document.md` §5). This document instead evaluates the real alternatives that were live options before choosing to build, to keep ADR-0001's reasoning honest and revisitable.

## 1. Alternatives evaluated

| Product | Model | Why it wasn't chosen |
|---|---|---|
| **Auth0** | Hosted, per-MAU pricing | Pricing scales against a portfolio of side projects rather than one funded product; vendor lock-in on the hardest component to migrate later |
| **Clerk** | Hosted, per-MAU pricing, strong DX (this project's explicit UX inspiration) | Same pricing/lock-in concern as Auth0; also closed-source, so "reuse across any language" is bounded by whatever SDKs Clerk chooses to ship. Deep-dive feature/criticism analysis: `clerk-feature-analysis.md` |
| **Keycloak** | Self-hosted, open source, Java/Quarkus-based | Mature and OIDC-conformant, but admin-console-first and heavyweight to extend with custom domain concepts (e.g. this project's `organization-module` shape); steep operational learning curve for a solo developer |
| **Zitadel** | Self-hosted or hosted, open source, Go-based | Strong multi-tenancy model (close to what `organization-module` wants), but a different language/ecosystem than the rest of this author's stack, adding an operational surface the author isn't already fluent in |
| **Ory (Kratos/Hydra)** | Self-hosted, open source, composed of separate services | Correct separation of concerns (identity vs. OAuth2), but the "compose several services yourself" model reintroduces integration complexity that a solo developer is trying to avoid |
| **SuperTokens** | Self-hosted or hosted, open source | Closest in spirit to "developer-first, reusable," but its core session model and extensibility patterns are less aligned with a standard OIDC-everything integration story than building directly on Spring Authorization Server |

## 2. Why "build on Spring Authorization Server" beats both ends of this table

Every self-hosted alternative in the table above is a **finished product** with its own opinions, admin UI, and extension model — adopting one means adapting to its shape. Every hosted alternative solves the pricing/control problem in the wrong direction for this project's constraints. Spring Authorization Server is neither — it's a **protocol-compliance library**: it gets the OAuth2/OIDC state machine, PKCE, and JWKS right (the part that's genuinely dangerous to get wrong by hand) while leaving the product (accounts, organizations, admin surface) to be built to this project's own shape. This is the "why build instead of adopt" reasoning from `vision-document.md` §4, restated with the concrete alternatives that made it a real decision rather than a default.

## 3. Positioning

Clavaris is not currently trying to compete with any product in this table commercially. It exists to serve this author's own portfolio of projects. Should that change (a real multi-tenant commercial offering), this document and the constraints in `project-charter.md` §6 would need a full revisit — that is a different project with different economics, not a natural extension of this one.

## 4. Observability as a real differentiator, not a checkbox

Reviewed 2026-09-14 (SDE-III pass, explicit request to evaluate this as a positioning point, not just re-confirm the engineering is real). TD-FUT-011 (`technical-debt-register.md` §6, closed 2026-08-24) shipped, and this review re-verified live rather than assumed from that entry's own text: 100% request tracing (`management.tracing.sampling.probability: 1.0` — every request, not a sample of them), real per-HTTP-endpoint p50/p95/p99 latency histograms (`management.metrics.distribution.percentiles-histogram.http.server.requests`, `application.yml`), ECS-formatted structured console logs (`logging.structured.format.console: ecs`) with `traceId`/`spanId` correlated into every log line a traced request emits, and a full self-hosted stack (Prometheus, Grafana, Zipkin, Alertmanager) wired to real alert rules that deliver real email — `docker-compose.observability.yml`, `infra/observability/`. None of this is aspirational documentation: `nfr-quality-attributes.md` §3's own p95 < 300ms target is measured by this exact pipeline, and TD-FUT-011's own closure record documents a real alert firing and a real email landing in a real inbox, not a config review.

This is worth naming as a genuine differentiator against every alternative in §1's table, not folded back into "engineering hygiene":

- **Against the self-hosted open-source options (Keycloak, Zitadel, Ory).** None ship 100%-sampled distributed tracing or per-endpoint latency percentiles out of the box — an operator wanting that has to bolt on and wire the same Micrometer/OpenTelemetry-plus-backend stack Clavaris already ships pre-integrated. Keycloak in particular is admin-console-first (§1); its own operational visibility is metrics-only unless an operator invests separately in tracing.
- **Against the hosted options (Auth0, Clerk).** A hosted IdP gives visibility into *its own* infrastructure via *its own* dashboard — never per-request traces or histograms scoped to how a specific tenant's traffic behaves against your own stack, and never in a format (ECS, Prometheus) that plugs into an operator's own existing observability tooling. Self-hosting Clavaris means the operator owns this data outright, correlated with everything else already running in the same Prometheus/Grafana estate — the same "own the stack, not just the outcome" reasoning ADR-0001 already gives for building Clavaris at all, extended to the operational side.
- **Why this is credible, not marketing copy.** Every claim above has a corresponding live-verification note in `technical-debt-register.md` TD-FUT-011's own closure record (§6) — a real fail-open event scraped by Prometheus, a real firing alert, a real email in a real inbox, a real traced request with genuine per-filter span timing in Zipkin. This document only restates what that entry already proved; it does not add a new claim of its own.

This section exists so a future pass evaluating Clavaris against a new alternative, or preparing any external-facing material (`long-term-commercialization-vision.md`'s own eventual direction), starts from "this is a real, verified strength" rather than rediscovering it from the technical debt register each time.
