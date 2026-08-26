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
| `GET /userinfo` | Authenticated account claims, per requested scopes |
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
| `POST /api/v1/admin/organizations` | Create a new `Organization` (tenant isolation boundary, ADR-0010) — operator-only in v1, mirroring manual `OAuthClient` registration; provisions the initial `SigningKey` and default `RateLimitPolicy` |
| `POST /api/v1/admin/organizations/{id}:delete` | Hard-delete an `Organization` and its entire owned account pool (BR-DATA-02/03's own organization-level equivalent) — the single most destructive operation this management API exposes, its own dedicated scope, deliberately separate from account deletion above |
| `POST /api/v1/admin/organizations/{id}/signing-keys:rotate` | Manually trigger signing-key rotation with overlap for this Organization (ADR-0010 §5.2) — audited; scheduled/unattended rotation is v1.1 |
| `PUT /api/v1/admin/organizations/{id}/rate-limit-policy` | Set this Organization's capacity-ceiling override (ADR-0010 §6.2) — operator-only in v1; never governs the fixed anti-abuse layer (§6.1), which is not configurable by anyone |
| `POST /api/v1/admin/organizations/{id}/workspaces` | Create a `Workspace` within this Organization (renamed from the pre-ADR-0010 "organization" concept) |
| `POST /api/v1/admin/workspaces/{id}/invitations` | Invite a member to a workspace |
| `DELETE /api/v1/admin/workspaces/{id}/members/{accountId}` | Remove workspace member (BR-WS-03: immediate access revocation) |
| `POST /api/v1/admin/organizations/{id}/clients` | Register a new `OAuthClient` under this Organization (manual/admin-only in v1, per `prd-mvp.md` §2.2). Optional `requireConsent` (default `true`, ADR-0017/TD-SEC-026) — an operator explicitly sets `false` to skip the end-user consent screen for a trusted first-party client. |
| `POST /api/v1/admin/webhook-endpoints` | Register a webhook endpoint for the calling client — 🟡 proposed, see ADR-0007 |
| `POST /api/v1/admin/webhook-endpoints/{id}/deliveries/{deliveryId}:replay` | Manually replay an `EXHAUSTED` delivery (BR-WEBHOOK-03) — 🟡 proposed, see ADR-0007 |

## 4. Authentication for the management API

Every management API call requires a valid access token issued via the `client_credentials` grant to a registered client with the appropriate scope — the same OAuth2 machinery as the primary surface, never a separate API-key scheme (ADR-0006). **v1: exclusively a `PlatformClient` token, issued by the platform issuer** (`{clavarisBaseUrl}/oauth2/token`, no `/o/{organizationId}` prefix — ADR-0010, Organization provisioning, BR-PLATFORM-01/02) — no `OAuthClient` belonging to any `Organization` is accepted here, not even for actions on its own `Organization`. This is what makes `POST /api/v1/admin/organizations` itself possible to authenticate: it can't be gated by a token belonging to the `Organization` it's about to create.

## 5. Error contract

Standard OAuth2 error responses (`error`, `error_description`) for the OIDC surface, per RFC 6749 §5.2. Management API errors follow a consistent JSON problem-detail shape (`type`, `title`, `status`, `detail`) — exact schema to be finalized alongside `docs/05-engineering/coding-standards.md`.

## 6. Open questions

- Exact scope naming convention for the management API (`org:write`, `org.write`, etc.) — not yet decided, low-risk to defer to implementation time.
- ~~Whether springdoc-openapi is worth adopting for the management API~~ — **resolved**: yes, decided in **ADR-0008** (OpenAPI 3.1 generated from code via springdoc-openapi, Swagger UI in dev/local only). The OIDC surface itself still doesn't need it — already self-describing via the discovery document.
