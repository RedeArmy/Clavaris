# ADR-0023: Organization-scoped admin credential (`OrganizationClient`) + API keys info endpoint

**Status:** ✅ Aprobado (2026-09-05)

## Context

Mirrors https://clerk.com/docs/guides/development/clerk-environment-variables: a Publishable key
(safe to share, frontend-facing), a Secret key (admin-level backend power — impersonation, security
settings, admin creation — scoped to one app), and an info panel (Frontend/Backend API URL, JWKS URL
+ public key, configured/latest API version).

Clavaris has real admin power today (`PlatformClient`, `client_credentials`-authenticated, reaches
`/api/v1/admin/**`) but it is **unscoped** — one `PlatformClient` token can act on every
`Organization` in the system. Clerk's Secret Key grants the same class of power (its own docs name
impersonation, security-setting changes, and admin-user creation explicitly) but bounded to one
app/instance. `BR-PLATFORM-02` already states, correctly, that no `OAuthClient` may reach this
surface even for its own Organization — that rule is untouched. What's missing is a *third*
credential shape, distinct from both `PlatformClient` (unscoped) and `OAuthClient` (end-user OIDC
login, no admin power at all): an Organization-bound admin credential.

Confirmed via research before designing: genuinely unbuilt. No ADR proposes it; `PlatformClient`'s
own migration comment explains why it deliberately has no `organization_id` column (creating an
Organization can't be gated by a token belonging to the Organization being created) — the same
reasoning that rules out simply adding a nullable column to that table instead of a new one.

## Decision

### 1. `OrganizationClient` — a new, separate credential type

Mirrors `PlatformClient` exactly (`client_credentials`, `CLIENT_SECRET_BASIC`, Argon2-hashed secret,
`allowedScopes`) plus one field, `organizationId`. `client_id` prefixed `sk_test_`/`sk_live_` per the
Organization's own environment — available in **both** environments, unlike ADR-0022's social
credentials (Clerk gives every app, dev or prod, its own Secret Key). `allowedScopes` reuses
`PlatformScopes`' own string constants verbatim: the *operations* are the same use cases
`PlatformClient` already reaches; this credential only narrows *which* Organization they may target.
Minted only by a `PlatformClient` (`POST
/api/v1/admin/organizations/{organizationId}/secret-keys`) — no self-service issuance in v1, same
deferral ADR-0010 already states for the rest of this surface.

### 2. Token issuance: one shared `client_credentials` endpoint, not a new issuer

`PlatformRegisteredClientRepository` resolves either credential type by `client_id` (namespaces never
collide: `sk_` prefix vs. `PlatformClient`'s unprefixed ids). A composed `OAuth2TokenCustomizer`
stamps `organization_id` onto an `OrganizationClient` token's own claims; absent entirely for a
`PlatformClient` token. Matches Clerk's own model exactly: one shared Backend API URL, reached with
either a test or live secret key, differentiated only by which credential is presented — not by URL.

### 3. Enforcement: one filter, fail-closed allowlist — not N controller retrofits

`OrganizationClientOwnershipFilter` (`AdminApiSecurityConfig`) resolves the request's target
Organization via a small, explicit route table (direct path-variable resolution, or a one-hop lookup
for `accountId`/`workspaceId`-keyed endpoints) and rejects (`403`) whenever an `OrganizationClient`
token's own `organization_id` claim doesn't match — **including when no resolver matches the request
at all**. This is a real allowlist: an endpoint never named here is unreachable by construction, not
by remembering to add a check to it later. v1's reachable set matches Clerk's own stated Secret-Key
powers (rate-limit/social-login/social-credentials/signing-keys management, workspace/client/webhook
registration, account impersonation, workspace-admin-member creation) — deliberately **excludes**
Organization lifecycle itself (create/delete/promote-to-production) and account
delete/suspend/reactivate, named here as a real, deliberate v1 boundary, not a silent gap.

### 4. API keys info endpoint — the primary visible deliverable

`GET /api/v1/admin/organizations/{organizationId}/api-keys`, entirely derived, no new storage beyond
§1: `publishableKey` (`pk_test_`/`pk_live_` + base64url(organizationId) — an opaque, non-secret
routing identifier, same shape Clerk's own key literally is), `frontendApiUrl`/`jwksUrl` (the
Organization's own already-live tenant issuer/JWKS, ADR-0010 §5), `backendApiUrl` (§2's shared token
endpoint), `jwksPublicKey` (the Organization's own active signing key, PEM-encoded, read via
identity-module's already-existing `OrganizationSigningKeyMaterialFactory`), `configuredApiVersion`/
`latestApiVersion` (ADR-0008's own URI-path versioning, `"v1"` today).

## Consequences

- **Positive:** a leaked `OrganizationClient` secret's blast radius is bounded to one Organization —
  a real, structural mitigation improvement over what a leaked `PlatformClient` secret does today,
  not just a policy statement.
- **Positive:** zero regression risk for the platform tier — every change here is additive
  (`PlatformClient` tokens carry no new claim, hit no new filter logic that changes their outcome).
- **Negative:** `OrganizationClientOwnershipFilter`'s own route table is now the single place that
  must be kept in sync with any new Organization-scoped admin endpoint that should be
  `OrganizationClient`-reachable — a real, ongoing maintenance surface, not a one-time cost. Missing
  an entry fails safe (403, not silently open), but a maintainer must still remember to add one for
  a genuinely-intended-to-be-reachable new endpoint.
- **Negative:** the v1 endpoint allowlist is narrower than Clerk's own real Secret-Key power (no
  Organization lifecycle, no account delete/suspend) — an accepted, named boundary, not a silent gap.

## Alternatives considered

- **A nullable `organization_id` column on `platform_clients` itself** — rejected: conflates two
  genuinely different trust boundaries on one table/type, and reopens exactly the circularity
  `PlatformClient`'s own migration comment already explains for why it has none.
- **Read-only info endpoint only, no new admin credential** — rejected per explicit user direction:
  Clerk's own Secret Key is real admin power, not just a routing identifier: a stopgap that only
  displays URLs would misrepresent what this feature claims parity with.
- **A parallel `OrganizationClientScopes` string namespace** — rejected: the operations
  `OrganizationClient` reaches are the exact same use cases `PlatformClient` already reaches; a
  second scope-string vocabulary for the same actions would be duplication with no real benefit.
