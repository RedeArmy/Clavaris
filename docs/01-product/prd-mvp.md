# PRD — Clavaris v1 (MVP)

🟡 En revisión

## 1. Purpose

Defines the exact functional scope of Clavaris v1 — the minimum that lets a real consumer application (JobSeeker) integrate a complete login/registration flow via standard OIDC, with no product-specific compromises baked into the protocol layer.

## 2. Scope by module

### 2.1 `identity-module`

| Capability | v1 | Notes |
|---|---|---|
| Register with email + password | ✅ | Argon2id hashing (ADR-0005); no plaintext ever stored or logged. Registration always happens in the context of one `Organization` (ADR-0010) — the same email can register independently in a different `Organization` with no linkage |
| Email verification | ✅ | Time-limited signed token, single-use; unverified accounts can authenticate but consuming apps decide whether to gate features on `email_verified_at` — that policy is the consumer's, not Clavaris's |
| Login with email + password | ✅ | Rate-limited from day one (BR-ID-06) |
| Social login (Google, GitHub) | ✅ | Account linking by verified email; a social login with an email matching an existing account links to it rather than creating a duplicate — ambiguity here is a real security question, see §5 open questions |
| Password recovery (forgot password) | ✅ | Time-limited signed token, single-use, invalidates all active sessions on successful reset |
| Session management (list/revoke active sessions) | ✅ | Minimal UI, functional not polished |
| Refresh token issuance + rotation | ✅ | Single-use, reuse-detection revokes all active tokens for the account (BR-ID-03) |
| MFA (TOTP) | ❌ backlog | Real gap, not silently dropped — flagged in roadmap as first post-v1 priority |
| Passkeys (WebAuthn, passwordless primary login) | ❌ v1.1 | `clerk-feature-analysis.md` §6 — real UX/security upgrade, not launch-blocking |
| Breached-password check at registration/password-change | ❌ v1.1 | BR-ID-07; k-anonymity-style check (e.g. HIBP range query, no plaintext password ever leaves the server) — `clerk-feature-analysis.md` §7 item 1 |
| New-device step-up MFA (force 2nd factor on unrecognized device even with correct password) | ❌ v1.1 | Scoped-down version of Clerk's "Client Trust" — `clerk-feature-analysis.md` §6 |
| User impersonation (admin support tool) | ❌ v1.1 | `BR-ADMIN-01`; bundled with the management-API audit-logging gap already flagged in `threat-model-stride.md` §5 — building both together, not sequenced apart |

### 2.2 `client-registry-module`

| Capability | v1 | Notes |
|---|---|---|
| Register an OAuth client (manual/admin, not self-service) | ✅ | `client_id`, `client_secret`, `redirect_uris`, allowed grants/scopes, assigned to exactly one `Organization` (ADR-0010) — one Organization can register several clients (e.g. web + mobile) sharing its isolated account pool |
| Authorization Code flow with PKCE | ✅ | The only supported interactive flow in v1 — no implicit flow, no resource owner password credentials grant (both are legacy/discouraged patterns) |
| `client_credentials` grant | ✅ | For the management API only (ADR-0006) |
| Discovery document, JWKS, `/userinfo`, `/revoke`, end-session endpoint | ✅ | Standard OIDC surface — required for "any language, standard client library" to actually be true |
| Self-service client registration UI | ❌ backlog | v1 clients are registered manually; a self-service developer console is a v1.1+ concern |
| Embedded, branded login (iframe-modal + per-client custom domain) | ❌ v1.1/v2, see ADR-0009 | Requires DNS/proxy domain verification and dynamic TLS — real infra work, not a UI-only feature |
| Per-client branding (logo, color, app name on hosted login/consent UI) | ❌ v1.1/v2, see ADR-0009 | `ClientBranding` entity, bundled with the custom-domain feature above |

### 2.3 `webhook-module` — 🟡 proposed, see ADR-0007

| Capability | v1 | Notes |
|---|---|---|
| Register a webhook endpoint per OAuth client | ✅ | URL + subscribed event types; auto-generated signing secret shown once at creation, only its hash stored (`data-model.md` §2 principle) |
| Signed event delivery | ✅ | HMAC-SHA256, `Clavaris-Signature` header, at-least-once (BR-WEBHOOK-01/02) |
| Event catalog v1 | ✅ | `account.created`, `account.email_verified`, `account.deleted`, `session.revoked`, `refresh_token.reuse_detected`, `membership.created`, `membership.removed`, `invitation.accepted` — mirrors `domain-model.md` §6's domain events |
| Retry with backoff + manual replay | ✅ | BR-WEBHOOK-03; replay available via management API, never a silent drop |
| Signing secret rotation | ❌ backlog | Flagged as an open question in ADR-0007; needed before that ADR can move to Aprobado |
| Delivery log UI (list/inspect past deliveries) | ❌ backlog | v1 exposes this via the management API only, no dedicated UI |

### 2.4 `organization-module` — 🟡 redefined by ADR-0010

`organization-module` now covers two distinct concepts — do not conflate them when reading this table:

| Capability | v1 | Notes |
|---|---|---|
| `Organization` = tenant isolation boundary (one per consuming system) | ✅ | ADR-0010. Manual/operator-created in v1, mirroring manual `OAuthClient` registration (§2.2) — no self-service tenant creation yet. Owns an isolated `Account` pool (`identity-module`) and an isolated set of `OAuthClient`s (§2.2). |
| Create workspace (within one Organization), invite members by email | ✅ | Renamed from "organization" to `Workspace` (ADR-0010) to free up that name for the tenant-isolation concept above |
| Workspace roles: `OWNER` / `ADMIN` / `MEMBER` | ✅ | Fixed set in v1, not configurable per-workspace |
| Remove workspace member, transfer workspace ownership | ✅ | |
| Custom/configurable roles per workspace | ❌ backlog | Real feature gap for future enterprise-shaped consumers, not needed by JobSeeker |

## 3. Primary user stories (developer of a consuming application)

- As a developer integrating a new application, I register an OAuth client and redirect users to `/authorize` using a standard OIDC library, with no Clavaris-specific SDK.
- As a developer, I call `/userinfo` or decode the ID token's claims to get the authenticated account's identity, and validate access tokens against the published JWKS without calling back to Clavaris on every request.
- As a developer building a multi-tenant product, I use `organization-module`'s workspace membership/role model instead of building my own from scratch — and I get full account-pool isolation from every other consuming system for free, by construction (ADR-0010).
- As a developer, I register a webhook endpoint for my application and receive signed `account.created`/`membership.created`/etc. events so I can keep a local read model in sync, without polling Clavaris (ADR-0007).
- As a developer, I explore the management API's exact request/response shapes via Swagger UI (`/swagger-ui.html`, dev/local only) instead of relying on hand-written docs that may drift (ADR-0008).
- As an operator, I can revoke a compromised account's sessions and force a password reset without touching the database directly.

## 3a. API contract and versioning — 🟡 proposed, see ADR-0008

| Capability | v1 | Notes |
|---|---|---|
| Management API versioned by URL path | ✅ | `/api/v1/...`; a breaking change ships as `/api/v2/...` alongside the still-running v1 |
| OpenAPI 3.1 spec generated from code (springdoc-openapi) | ✅ | `/v3/api-docs`; annotation-driven, cannot silently drift from the running controller |
| Swagger UI | ✅ dev/local only | Disabled in production by default — same scoping rationale as no self-service client console in v1 |
| OIDC surface itself versioned | ❌ not applicable | Protocol-defined by the OpenID/OAuth2 specs (ADR-0006); conformance requires matching the spec exactly, not a Clavaris versioning decision |

## 4. Out of scope for v1 (explicit, not implicit)

SAML, SCIM provisioning, self-service client registration console, configurable per-organization roles, MFA, multi-region deployment. Every one of these is a real, legitimate feature — deferred because no current consumer needs it yet, not because it's unimportant. See `docs/01-product/roadmap-and-release-plan.md` for sequencing.

**Explicit non-goals, not just deferrals** (per `clerk-feature-analysis.md` §6, informed by what Clerk bundles into its product that Clavaris deliberately won't): billing/subscription management, feature/plan gating, and "waitlist" launch-gating. These are product-specific business logic that belongs in each consumer's own backend — bundling them here would violate this project's own core boundary ("Clavaris doesn't know what a candidate is"), not a scope gap to close later.

## 5. Open questions

- Social-login account-linking-by-email: is matching by verified email alone sufficient, or does it need an explicit "confirm this is you" step to prevent a scenario where an attacker who controls a not-yet-verified email pre-registers to intercept a future social login? Needs resolution before social login ships, not after.
- Should unverified accounts be allowed to issue tokens at all, or should Clavaris itself gate on `email_verified_at` rather than leaving it to consumers? Currently leaning "leave it to consumers" (Clavaris has no product opinion), but worth revisiting once JobSeeker's own verification requirements are finalized.
