# ADR-0008: API versioning strategy and OpenAPI/Swagger as the contract source of truth

**Status:** 🟡 Propuesta — pendiente de revisión antes de considerarse ✅ Aprobado y añadirse a la lista de ADRs vigentes del proyecto

## Context

Clavaris exposes two API surfaces with different versioning needs:

1. The **standard OIDC/OAuth2 surface** (`/authorize`, `/token`, `/userinfo`, `/jwks.json`, `.well-known/openid-configuration`, `/revoke`, end-session) — this is protocol-defined by the OpenID/OAuth2 specs themselves. It is **not** versioned by Clavaris; conformance requires these paths and shapes to match the spec exactly (ADR-0006, `vision-document.md` §2). Any "versioning" concern here is a spec-conformance concern, not a product decision.
2. The **management API** (organizations, invitations, user administration) and the **webhook payload catalog** (ADR-0007) — both Clavaris-specific contracts (ADR-0006 §Consequences already flags this), and both will change over time as the product grows. These need an explicit, deliberate versioning story.

Separately, the project's own "integration cost for a new consumer" success metric ("under a day, no custom SDK") only holds if the management API and webhook contracts are **discoverable and machine-readable**, not just described in prose docs that drift from the real implementation.

## Decision

### 1. Versioning: URI path versioning for the management API

The management API is versioned in the URL path, nested under the existing `/admin` prefix (`api-contract-overview.md` §3): `/api/v1/admin/organizations`, `/api/v1/admin/clients`, etc. A breaking change to a resource's shape or behavior ships as `/api/v2/admin/...` alongside the still-running `/api/v1/admin/...`, kept alive for a documented deprecation window (length TBD per change, announced in release notes — not fixed here as a blanket policy since breaking-change severity varies).

Chosen over header/media-type versioning (`Accept: application/vnd.clavaris.v1+json`) because it is trivially discoverable and testable (curl, browser, Postman — no custom `Accept` header needed to exercise a version), which matters directly for the "integrate in under a day" goal — a developer exploring the API for the first time sees the version in the URL without reading integration docs first.

Additive, non-breaking changes (new optional field, new endpoint) do **not** require a version bump — standard "be liberal in what you accept" API evolution, consistent with not forcing every consumer to track every release.

### 2. Webhook payloads version independently (ADR-0007 §3)

Each webhook event carries its own `api_version` field, decoupled from the management API's path version — an event catalog addition never forces a management API version bump and vice versa.

### 3. OpenAPI 3.1 as the single contract source of truth, generated from code

**springdoc-openapi** (the maintained successor to springfox, already integrates cleanly with Spring Boot 3.4 / Spring Authorization Server) generates the OpenAPI 3.1 spec from controller annotations (`@Operation`, `@Schema`, `@ApiResponse`) at build/runtime, exposed at `/v3/api-docs` with **Swagger UI** at `/swagger-ui.html` for the management API. This is annotation-driven (code-first), not a hand-maintained YAML file — the spec cannot silently drift from what the controller actually does, because the same annotations that produce the spec are load-bearing on the endpoint itself.

The OIDC surface (item 1 above) is **not** documented via this same Swagger UI — its contract is the OpenID/OAuth2 specs themselves plus the discovery document Spring Authorization Server already publishes; duplicating that into hand-written OpenAPI would be redundant and a drift risk of its own.

Swagger UI for the management API is **disabled in production by default**, enabled only in local/dev profiles — the management API's discoverability goal is for integrating developers during setup, not a public-facing surface (mirrors the "no self-service client registration in v1" scoping decision, `prd-mvp.md` §2.2).

## Consequences

- **Positive:** the management API contract is always accurate because it's generated from the same code that serves requests — no separate "keep the docs in sync" discipline required.
- **Positive:** URI versioning is the lowest-friction option for a developer integrating "in under a day" — no custom headers, no content-negotiation surprises.
- **Positive:** running two management API versions side by side is a normal Spring MVC routing concern (separate `@RequestMapping` base paths or separate controller packages per version), not an architectural rework.
- **Negative:** URI versioning means the URL itself changes on a breaking version bump, which is a real migration cost for consumers (vs. header versioning, where the URL is stable) — accepted because discoverability was weighted higher than migration ergonomics for this project's stage (few consumers, solo maintainer, `nfr-quality-attributes.md` §6).
- **Negative:** running two live versions of the same resource simultaneously means two code paths to maintain and test until the deprecation window closes — a real ongoing cost, not free; version bumps should be rare and deliberate, not a default response to any change.

## Alternatives considered

- **Header/media-type versioning** (`Accept: application/vnd.clavaris.v1+json`) — rejected for v1: harder to explore/test manually, and no material benefit at this project's current consumer count to offset that cost. Revisit only if a specific consumer need surfaces.
- **No versioning, additive-only forever** — rejected: unrealistic long-term promise for any API that's expected to evolve; better to have an unused-but-ready mechanism than to be forced into a painful retrofit later.
- **Hand-maintained OpenAPI YAML, contract-first** — rejected for v1: real teams do this well, but it requires discipline (a solo developer) to keep two artifacts (code, spec) in sync; springdoc's code-first generation removes that failure mode entirely at the cost of slightly less control over exact spec wording, an acceptable trade at this project's scale.
