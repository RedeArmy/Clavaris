# Business Rules — Clavaris

🟡 En revisión

Rule IDs are referenced from code comments per `CLAUDE.md` §8. Grouped by module.

## Identity (`BR-ID`)

- **BR-ID-01** — A password is never stored or logged in plaintext, anywhere, under any circumstance, including error logs and stack traces. Hashed with Argon2id (ADR-0005) before it touches persistence.
- **BR-ID-02** — An account may have multiple authentication methods (password, one or more social identities) simultaneously; removing one method requires at least one other to remain, so an account can never end up with zero ways to authenticate.
- **BR-ID-03** — Refresh tokens are single-use. Reuse of an already-rotated refresh token is treated as a compromise signal and revokes every active token for that account, not just the reused one (`CLAUDE.md` §5).
- **BR-ID-04** — A password reset token is single-use, time-limited, and invalidates all of the account's active sessions and refresh tokens on successful reset — a password reset is treated as "assume prior sessions may be compromised," not just "change the credential."
- **BR-ID-05** — Email verification and password reset tokens are delivered only to the email address of record at time of request, never returned in an API response body — the token must not be observable by anything except the recipient's inbox.
- **BR-ID-06** — Rate limiting on login and token endpoints is mandatory from the first deployment, not added later once abuse is observed (`CLAUDE.md` §6). Rate limiting must never throttle a legitimate token-refresh cycle for an already-active session — a documented failure mode in a comparable system (`clerk-feature-analysis.md` §7 item 2) that turned rate limiting into a self-inflicted outage. Since ADR-0010 §6, this is two layers, not one: a fixed, system-defined anti-abuse threshold per `(organization_id, account_or_ip_identifier)` that no tenant can loosen (the actual credential-stuffing defense), plus a separate per-`organization_id` capacity ceiling (tunable within a system-wide cap, operator-only in v1, BR-ORG-05) so one tenant's attack or traffic spike can never exhaust another tenant's budget.
- **BR-ID-07** — 🟡 v1.1, see `prd-mvp.md` §2.1. A password is checked against a breached-password corpus (k-anonymity range query, e.g. HIBP-style — the full password never leaves the server, only a truncated hash prefix) at registration and password-change time; a match is rejected with a generic "choose a different password" message, never revealing the source of the match.

## Organizations — tenant isolation (`BR-ORG`) — 🟡 see ADR-0010

- **BR-ORG-01** — Every `Account` belongs to exactly one `Organization`; there is no cross-organization identity — the same email address may exist as entirely independent `Account` rows in different organizations, with no linkage, shared session, or shared credential between them.
- **BR-ORG-02** — Every `OAuthClient` belongs to exactly one `Organization`. The hosted login page for a given `client_id` only ever authenticates against that client's own `Organization`'s account pool — a login screen never has a code path capable of seeing or authenticating an `Account` from a different `Organization`.
- **BR-ORG-03** — Gaining access to a second `Organization` requires a separate, explicit registration in that organization — never an invitation, membership grant, or any mechanism that reuses an existing `Account` across organizations.
- **BR-ORG-04** — Every `Organization` has its own RS256 signing key pair, its own issuer (`{clavarisBaseUrl}/o/{organizationId}`, path-based per ADR-0010 §5.1), and its own JWKS document; a token issued under one Organization's key is never verifiable against another Organization's JWKS. Key rotation with overlap (`CLAUDE.md` §6) runs independently per Organization; v1 rotation is a manually-triggered, audited management-API operation, not a scheduled job (ADR-0010 §5.2).
- **BR-ORG-05** — Rate limiting on login/token endpoints is enforced in two layers per Organization (ADR-0010 §6): a fixed, system-defined anti-abuse layer keyed by `(organization_id, account_or_ip_identifier)` that is **never** tenant-configurable (BR-ID-06), and a per-`organization_id` aggregate capacity ceiling that an Organization may tune within a hard system-wide cap. In v1, only the capacity ceiling exists as a configurable `RateLimitPolicy`, and only a Clavaris operator can set it — tenant self-service is deferred to v1.1, gated on audit logging of every change.
- **BR-ORG-06** — `Organization` creation is a Clavaris-operator-only action (`POST /api/v1/admin/organizations`); on success, its initial `SigningKey` is generated and activated synchronously, in the same operation — an `Organization` that exists but cannot yet issue a token is never an observable state. No `RateLimitPolicy` row is created at this step (BR-ORG-05's system default already covers it). Registering the Organization's first real `OAuthClient` is a separate, subsequent operator action, not bundled into provisioning.

## Platform tier (`BR-PLATFORM`) — see ADR-0010, Organization provisioning

- **BR-PLATFORM-01** — Operations that create or manage `Organization` rows themselves (including `Organization` creation) can never be authenticated by a token that belongs to an `Organization` — doing so would let a compromised tenant client reach across the isolation boundary BR-ORG-01–03 exist to establish. These operations are authenticated exclusively via a `PlatformClient`, issued a token by a dedicated platform issuer structurally separate from every tenant's own OIDC surface (never `/o/{organizationId}/...`).
- **BR-PLATFORM-02** — The entire `/api/v1/admin/*` management-API surface accepts platform-tier tokens only in v1 — no `OAuthClient` belonging to any `Organization` may call it, not even to manage its own `Organization`. Tenant self-service is v1.1+ scope, already deferred for other reasons (self-service client console, self-service `RateLimitPolicy`); this is the same deferral applied consistently, not a new exception.
- **BR-PLATFORM-03** — The first `PlatformClient` is provisioned from deployment-environment configuration (`PLATFORM_BOOTSTRAP_CLIENT_ID`/`PLATFORM_BOOTSTRAP_CLIENT_SECRET`) via an idempotent startup check, never via an HTTP endpoint and never via a credential shipped in code — it is the one trust root in the system that cannot derive from anything else already inside it.

## Workspaces (`BR-WS`) — renamed from the pre-ADR-0010 `BR-ORG` rules; a Workspace is a team/company grouping *inside* one Organization's account pool, not the tenant boundary itself

- **BR-WS-01** — Every workspace has exactly one `OWNER` at all times; ownership transfer is atomic (the previous owner becomes `ADMIN` in the same operation, never a moment with zero owners).
- **BR-WS-02** — An invitation is scoped to a specific email address and workspace, expires after a configurable window, and is consumed exactly once. Because the invited `Account` already belongs to the same `Organization` as the workspace (BR-ORG-01), an invitation can never cross a tenant boundary.
- **BR-WS-03** — Removing a member revokes that member's access to the workspace's resources immediately — not on next token refresh, since a token already issued could otherwise remain valid for the workspace's scope until natural expiry.

## Client registry (`BR-CLIENT`)

- **BR-CLIENT-01** — A registered client's `redirect_uris` are an exact-match allowlist; no wildcard or partial matching, to close the standard OAuth2 open-redirect attack class.
- **BR-CLIENT-02** — A token issued for one `client_id` is never valid against another client's resources without an explicit, separately-designed cross-client mechanism (not present in v1) — see `CLAUDE.md` §5. Since ADR-0010, this is additionally structural whenever the two clients belong to different `Organization`s: the token's `Account` doesn't exist outside its own `Organization`, so cross-organization validity isn't just disallowed, it's meaningless.
- **BR-CLIENT-03** — The Authorization Code flow requires PKCE for every client, including confidential clients — defense in depth, not just a public-client requirement, since it costs nothing and closes an entire attack class outright.
- **BR-CLIENT-04** — 🟡 v1.1/v2, see ADR-0009. A production `OAuthClient` using the embedded iframe-modal login experience must have a verified custom domain (`CNAME` or `PROXY` mode) — `SHARED` mode (Clavaris's own domain) is development-only, because third-party cookie blocking makes the embedded experience silently degrade in production without a clear failure signal.
- **BR-CLIENT-05** — 🟡 v1.1/v2, see ADR-0009. Social login (Google, GitHub, etc.) never renders inside the embedded iframe — it always opens as a full-navigation popup window. This is a hard external constraint (providers block iframe-embedded consent screens as an anti-clickjacking measure), not a design choice Clavaris can relax.

## Data protection and account deletion (`BR-DATA`)

- **BR-DATA-01** — No PII (passwords, tokens, email addresses in bulk) appears in application logs, ever — mirrors JobSeeker's own BR-DATA-01, restated here because it's independently enforced in this codebase, not inherited.
- **BR-DATA-02** — Clavaris exposes an authenticated admin API endpoint for account deletion, used by consuming applications (e.g. JobSeeker, per its own ADR-0013) to trigger identity deletion once their own local anonymization/grace-period process completes. Clavaris does not run its own independent grace period — the consumer owns that decision; Clavaris deletes on request.
- **BR-DATA-03** — Deleting an account via the admin API cascades: all sessions and refresh tokens are revoked immediately, all workspace memberships are removed, and the account's own record is hard-deleted (not anonymized) — see the flagged caveat in JobSeeker's ADR-0013.
- **BR-DATA-04** — Social identity links are deleted alongside the account; no orphaned linkage rows survive account deletion.

### Open question flagged by this rule set — resolved by ADR-0010

BR-DATA-02/03 previously flagged an open question: what happens if the *same person* has one Clavaris account used to log into two different consumers, and a deletion request from one consumer's data-deletion flow shouldn't silently delete an identity the other consumer depends on. ADR-0010 resolves this by construction — `Account` is scoped to exactly one `Organization` (one consuming system), so there is no scenario where one `Account` row is shared across two consumers in the first place. A deletion request against one `Organization`'s account can never affect another `Organization`.

## Admin support tooling (`BR-ADMIN`) — 🟡 proposed, v1.1, see `clerk-feature-analysis.md` §6

- **BR-ADMIN-01** — User impersonation (an operator signing in *as* an account to debug a reported issue) requires its own elevated management-API scope, is logged as a distinct audit-log event type (account, operator, start/end time), and expires after a short fixed inactivity timeout (mirrors the gap already flagged in `threat-model-stride.md` §5 — audit logging for the management API — built together with this feature, not after it).
- **BR-ADMIN-02** — An impersonation session is never indistinguishable from the real account's own session to anything downstream: any token issued during impersonation carries a claim marking it as such, so a consumer application can choose to restrict what an impersonated session is allowed to do (e.g. block payment actions), rather than this being silently invisible.

## Webhooks (`BR-WEBHOOK`) — 🟡 proposed with ADR-0007, not yet implemented

- **BR-WEBHOOK-01** — Every webhook payload is signed HMAC-SHA256 with the receiving endpoint's own secret (`Clavaris-Signature` header, timestamp + signature); a consumer that does not verify this signature is trusting an unauthenticated HTTP request, so the signing step is never optional or skippable per-endpoint.
- **BR-WEBHOOK-02** — Delivery is at-least-once, never at-most-once. Every event carries a stable `event.id`; consumers are responsible for deduplicating on it. Clavaris does not promise exactly-once delivery — that promise cannot be made honestly over plain HTTP with retries.
- **BR-WEBHOOK-03** — A failed delivery (non-2xx response or timeout) retries with exponential backoff and jitter up to a fixed attempt limit; after the final attempt it is marked `EXHAUSTED`, never silently dropped, and stays visible for manual replay via the management API.
- **BR-WEBHOOK-04** — Clavaris never writes directly to a consumer's database, filesystem, or infrastructure. The only integration surfaces are the signed webhook payload and the standard OIDC/management APIs — preserves the "any language, no special access" reusability goal (ADR-0001, ADR-0006, `CLAUDE.md` §1).
- **BR-WEBHOOK-05** — The outbox row for a domain event is written in the same database transaction as the state change that produced it (ADR-0007 §1) — an event is never observably lost because the process crashed between "state committed" and "event published."
