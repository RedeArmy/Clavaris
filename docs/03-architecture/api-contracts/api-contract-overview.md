# API Contract Overview — Clavaris

🟡 En revisión

## 1. Surface areas

Two distinct API surfaces, per ADR-0006:

1. **Standard OIDC/OAuth2 surface** — the primary integration point for any consumer application. Fully conformant with the OpenID Connect discovery + Authorization Code + PKCE flow. No consumer-specific behavior lives here.
2. **Management API** — organizations, invitations, user administration, client registration. Consumer-facing (a consumer's backend calls this, not its end users' browsers), protected via the `client_credentials` grant.

## 2. OIDC/OAuth2 endpoints (v1)

**Every endpoint below is scoped under a per-`Organization` issuer path, `{clavarisBaseUrl}/o/{organizationId}` (ADR-0010 §5.1) — not a flat, Clavaris-wide path.** A consumer's `OAuthClient` belongs to exactly one `Organization`, and its discovery URL is `{clavarisBaseUrl}/o/{organizationId}/.well-known/openid-configuration`; every other endpoint below is discovered from that document, never hardcoded relative to a shared root.

**`POST /oauth2/token` is confidential-client-only (ADR-0013)** — it requires `client_secret_basic`, so it must be called server-side by the consumer's own backend, never from browser JavaScript. There is no public/PKCE-only client type and no CORS policy on this or any other endpoint below.

| Endpoint (relative to `/o/{organizationId}`) | Purpose |
|---|---|
| `GET /.well-known/openid-configuration` | Discovery document — every other endpoint URL is discoverable from here, no hardcoding expected in consumers |
| `GET /oauth2/authorize` | Authorization Code flow entry point (PKCE `code_challenge` required — BR-CLIENT-03) |
| `POST /oauth2/token` | Code exchange, refresh token rotation |
| `GET /userinfo` | Authenticated account claims, per requested scopes. BR-WS-06: also carries `workspace_id`/`workspace_role` (not scope-gated) for any Account with a Workspace membership — the login-time signal a consuming application uses to decide whether to show its own admin panel; same claims already present on the ID token and access token from the preceding code exchange |
| `GET /oauth2/jwks` | Public keys for access/ID token signature verification (RS256 — ADR-0002), scoped to this Organization only (ADR-0010 §5) |
| `POST /oauth2/revoke` | Token revocation |
| `GET /connect/logout` | End-session (OIDC RP-initiated logout) |

### 2a. Platform issuer (ADR-0010, Organization provisioning) — a third surface, not a consumer-facing one

`{clavarisBaseUrl}/oauth2/token` and `{clavarisBaseUrl}/oauth2/jwks` (root path, **not** `/o/{organizationId}`-prefixed) issue and publish keys for `PlatformClient`s only (BR-PLATFORM-01) — never for any `Organization`'s end-user accounts, never reachable through any tenant's own OIDC flow. This is the token that authenticates §3's management API, including the one call (`POST /api/v1/admin/organizations`) that by definition can't be authenticated by a token belonging to the `Organization` it's about to create. Not part of "Surface areas" in §1 above because it isn't consumer-facing at all — it exists solely to bootstrap and operate the platform itself.

## 3. Management API (v1, illustrative — not final)

Paths are versioned per ADR-0008: `/api/v{n}/admin/...`. A version bump only happens on a breaking change; the `/admin` segment is the existing management-API grouping, unchanged by versioning.

Since ADR-0010, `organization` (tenant isolation boundary) and `workspace` (team/company grouping *within* one Organization) are distinct resources — do not conflate the two endpoint groups below.

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/admin/accounts/{id}:delete` | Account deletion, cascades per BR-DATA-03 — this is the endpoint JobSeeker's own ADR-0013 cascade calls once its local anonymization completes |
| `POST /api/v1/admin/accounts/{id}:suspend` | BR-ID-08: reversible ban — revokes live sessions/tokens immediately, blocks future logins |
| `POST /api/v1/admin/accounts/{id}:reactivate` | BR-ID-08: reverses a previous suspension |
| `POST /api/v1/admin/organizations` | Create a new `Organization` (tenant isolation boundary, ADR-0010) — operator-only in v1, mirroring manual `OAuthClient` registration; provisions the initial `SigningKey` and default `RateLimitPolicy` |
| `POST /api/v1/admin/organizations/{id}:delete` | Hard-delete an `Organization` and its entire owned account pool (BR-DATA-02/03's own organization-level equivalent) — the single most destructive operation this management API exposes, its own dedicated scope, deliberately separate from account deletion above |
| `POST /api/v1/admin/organizations/{id}/signing-keys:rotate` | Manually trigger signing-key rotation with overlap for this Organization (ADR-0010 §5.2) — audited; scheduled/unattended rotation is v1.1 |
| `PUT /api/v1/admin/organizations/{id}/rate-limit-policy` | Set this Organization's capacity-ceiling override (ADR-0010 §6.2) — operator-only in v1; never governs the fixed anti-abuse layer (§6.1), which is not configurable by anyone |
| `POST /api/v1/admin/organizations/{id}/workspaces` | Create a `Workspace` within this Organization (renamed from the pre-ADR-0010 "organization" concept) |
| `GET /api/v1/admin/organizations/{id}/workspaces` | List this Organization's `Workspace`s — read-only, no dedicated scope |
| `POST /api/v1/admin/workspaces/{id}/members` | BR-WS-04: add a member — provisions a real `Account` and triggers its password-reset email; there is no invitation step in v1 (BR-WS-02, deferred) |
| `GET /api/v1/admin/workspaces/{id}/members` | List a `Workspace`'s members — read-only, no dedicated scope |
| `PUT /api/v1/admin/workspaces/{id}/members/{accountId}/role` | Change a member's role (`ADMIN`/`MEMBER` only, BR-WS-05) — rejected if it would leave zero `ADMIN`s (BR-WS-01) |
| `POST /api/v1/admin/workspaces/{id}/members/{accountId}:remove` | Remove a workspace member (BR-WS-03) — removes only the membership, never the `Account` itself; rejected if it would leave zero `ADMIN`s (BR-WS-01). `:remove` custom-method naming, same precedent as `:delete` above |
| `POST /api/v1/admin/organizations/{id}/clients` | Register a new `OAuthClient` under this Organization (manual/admin-only in v1, per `prd-mvp.md` §2.2). Optional `requireConsent` (default `true`, ADR-0017/TD-SEC-026) — an operator explicitly sets `false` to skip the end-user consent screen for a trusted first-party client. |
| `POST /api/v1/admin/organizations/{id}/webhook-endpoints` | Register a `WebhookEndpoint` under this Organization — ✅ shipped, ADR-0007. Returns the raw `signingSecret` exactly once. |
| `GET /api/v1/admin/organizations/{id}/webhook-endpoints` | List this Organization's `WebhookEndpoint`s — read-only, no dedicated scope; never returns a secret |
| `POST /api/v1/admin/webhook-endpoints/{id}:rotate-secret` | Rotate a `WebhookEndpoint`'s signing secret (dual-secret overlap window, ADR-0007) — returns the new raw secret exactly once |
| `POST /api/v1/admin/webhook-endpoints/{id}:deactivate` | Reversibly stop delivering to this endpoint (its own configuration/history is untouched) |
| `POST /api/v1/admin/webhook-endpoints/{id}:activate` | Reverse `:deactivate` |
| `GET /api/v1/admin/webhook-endpoints/{id}/deliveries` | List recent deliveries for one endpoint (debugging/observability), newest first |
| `POST /api/v1/admin/webhook-endpoints/{id}/deliveries/{deliveryId}:replay` | Manually replay a past delivery (BR-WEBHOOK-03) — ✅ shipped, ADR-0007 |

## 4. Authentication for the management API

Every management API call requires a valid access token issued via the `client_credentials` grant to a registered client with the appropriate scope — the same OAuth2 machinery as the primary surface, never a separate API-key scheme (ADR-0006). **v1: exclusively a `PlatformClient` token, issued by the platform issuer** (`{clavarisBaseUrl}/oauth2/token`, no `/o/{organizationId}` prefix — ADR-0010, Organization provisioning, BR-PLATFORM-01/02) — no `OAuthClient` belonging to any `Organization` is accepted here, not even for actions on its own `Organization`. This is what makes `POST /api/v1/admin/organizations` itself possible to authenticate: it can't be gated by a token belonging to the `Organization` it's about to create.

## 5. Error contract

Standard OAuth2 error responses (`error`, `error_description`) for the OIDC surface, per RFC 6749 §5.2. Management API errors follow a consistent JSON problem-detail shape (`type`, `title`, `status`, `detail`) — exact schema to be finalized alongside `docs/05-engineering/coding-standards.md`.

## 6. Workspace membership changes are not instant token revocation (TD-WS-002)

**Read this before gating any of your own application's authorization decisions on Workspace
membership.** Clavaris tokens (access/ID/refresh) are `Account`-scoped per `Organization`
(ADR-0010) — **never** `Workspace`-scoped. There is no per-Workspace grant for Clavaris to revoke,
by design (BR-WS-03): v1 deliberately has no workspace-scoped token/authorization concept at all.
Concretely, this means:

- Removing a member from a `Workspace` (`POST /api/v1/admin/workspaces/{id}/members/{accountId}:remove`)
  or changing their role (`PUT .../role`) updates the `Workspace`'s own membership list
  immediately and fires a `workspace_membership.removed`/`workspace_membership.role_changed` event
  (outbox row, and — once you register a `WebhookEndpoint`, ADR-0007 — a real signed webhook
  delivery) synchronously with the change.
- It does **not** invalidate any access/ID/refresh token already issued to that `Account`. A
  still-valid token remains cryptographically valid, and any `workspace_id`/`workspace_role` claim
  it carries (BR-WS-06) reflects membership as of the moment that token/claim was minted, not
  necessarily right now — the claim is refreshed on the account's *next* login or token refresh,
  not pushed out to tokens that already exist.

**What your application must do if it gates authorization by Workspace membership:**

1. **Preferred — subscribe to the webhook events.** Register a `WebhookEndpoint` (§3 above)
   subscribed to `workspace_membership.removed` and `workspace_membership.role_changed`, and on
   receipt, immediately stop honouring that account's old Workspace-scoped access in your own
   system — do not wait for its Clavaris token to expire or refresh. This is near-real-time (the
   dispatcher polls on a few-second interval, ADR-0007) but is still eventually consistent, not a
   synchronous revocation — treat delivery latency and the retry window (BR-WEBHOOK-03) as real,
   not zero.
2. **Belt-and-suspenders — re-check membership on sensitive actions.** For anything where stale
   authorization would be a real problem (not just cosmetic), call
   `GET /api/v1/admin/workspaces/{id}/members` (or track membership via the webhook events above)
   rather than trusting a token's own `workspace_role` claim alone for that decision.

Neither path is optional if your application's own security model depends on Workspace membership
being current — the `workspace_id`/`workspace_role` claim (BR-WS-06) is a convenience signal for
UI decisions (e.g. "show this account's own admin panel"), not a revocation mechanism.

## 7. Open questions

- Exact scope naming convention for the management API (`org:write`, `org.write`, etc.) — not yet decided, low-risk to defer to implementation time.
- ~~Whether springdoc-openapi is worth adopting for the management API~~ — **resolved**: yes, decided in **ADR-0008** (OpenAPI 3.1 generated from code via springdoc-openapi, Swagger UI in dev/local only). The OIDC surface itself still doesn't need it — already self-describing via the discovery document.
