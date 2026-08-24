# Threat Model (STRIDE) — Clavaris

🟡 En revisión

STRIDE analysis across the highest-value attack surfaces: authentication, token issuance, session management, and client/organization administration. This is Clavaris's own threat model — distinct from and more security-critical than any single consumer's, since a defect here has blast radius across every consuming application.

## 1. Account authentication (login, registration, password reset)

| Threat | Scenario | Mitigation |
|---|---|---|
| **S**poofing | Credential stuffing against `/login` using leaked password lists | Rate limiting tuned specifically against credential stuffing (BR-ID-06), account lockout/backoff after repeated failures |
| **S**poofing | Social login account takeover via unverified email pre-registration | Flagged open question, `prd-mvp.md` §5 — resolution required before social login ships |
| **T**ampering | Password reset token guessed or brute-forced | Cryptographically random, sufficiently long token; hashed at rest (`data-model.md` §2); single-use; time-limited (BR-ID-04) |
| **R**epudiation | User disputes having requested a password change | Structured audit log of every auth event (NFR §5), without ever logging the credential itself |
| **I**nformation disclosure | Password or token value appears in logs or error responses | BR-DATA-01, BR-ID-05: passwords/tokens never logged, never returned in API responses |
| **I**nformation disclosure | Timing attack distinguishing "account exists" vs. "wrong password" on login | Constant-time comparison for password verification (inherent to `Argon2PasswordEncoder`); identical response shape/timing for "unknown email" vs. "wrong password" |
| **D**enial of service | Registration endpoint flooded to exhaust email-sending quota | Rate limiting on registration and verification-email resend, independent of login rate limits |
| **E**levation of privilege | Password reset flow used to take over an account without proving control of the original credential | Reset invalidates all sessions/refresh tokens on completion (BR-ID-04) — limits blast radius even if this control were bypassed |

## 2. Token issuance and refresh

| Threat | Scenario | Mitigation |
|---|---|---|
| **S**poofing | Forged access token accepted by a consumer | RS256 signing (ADR-0002) — forgery requires the private key, never distributed to consumers |
| **T**ampering | Refresh token stolen and replayed after legitimate rotation | Single-use rotation + reuse detection revokes the entire token family (BR-ID-03) |
| **I**nformation disclosure | Authorization code intercepted in transit (e.g. via a malicious redirect) | PKCE mandatory for every client, including confidential ones (BR-CLIENT-03) — an intercepted code is useless without the original `code_verifier` |
| **I**nformation disclosure | Signing key compromise | Key rotation with overlap; private key never stored in the database (`data-model.md` §2); compromise response follows `incident-response-signing-key-compromise.md` |
| **I**nformation disclosure | Postgres compromise directly exposes every currently-valid access/refresh/ID token | TD-SEC-019 (closed): `oauth2_authorization` (TD-SEC-003) now stores an HMAC-SHA256 digest of every access/authorization-code/ID-token value, never the literal bearer value (`HashedTokenOAuth2AuthorizationService`, `security-architecture.md` §2) — matching every other token table this project controls the schema for |
| **D**enial of service | `/oauth2/token` flooded to exhaust compute (RS256 verification/signing cost) | Rate limiting on the token endpoint (BR-ID-06) |
| **E**levation of privilege | Token issued for one client accepted as valid for another client's protected resource | BR-CLIENT-02 — cross-client validity requires an explicit, currently unbuilt mechanism; no implicit trust between clients |

## 3. Session management

| Threat | Scenario | Mitigation |
|---|---|---|
| **S**poofing | Session fixation | New session identifier issued on every authentication, never reused from a pre-auth state |
| **T**ampering | Client-side session state manipulation | Sessions are server-side records (`Session` entity), never trust client-supplied session claims beyond the signed token itself |
| **D**enial of service | Mass forced logout via revocation endpoint abuse | `/oauth2/revoke` requires the token owner's own valid credentials — not exposed as an unauthenticated action |

## 4. Client, organization, and platform administration

| Threat | Scenario | Mitigation |
|---|---|---|
| **S**poofing | Malicious redirect URI registered to exfiltrate authorization codes | Exact-match `redirect_uris` allowlist, no wildcards (BR-CLIENT-01) |
| **T**ampering | Workspace membership role escalated without authorization | Role changes gated by existing `OWNER`/`ADMIN` permission checks in the application layer, never client-trusted |
| **E**levation of privilege | Last `OWNER` removed, leaving a workspace ownerless and open to a race condition on next ownership claim | BR-WS-01 (renamed from the pre-ADR-0010 `BR-ORG-01`): atomic ownership transfer, never a zero-owner state |
| **R**epudiation | Admin action (client registration, member removal) disputed later | Audit logging of management API actions — not yet formally designed, flagged as an open gap alongside the key-compromise runbook above |
| **E**levation of privilege | `PlatformClient` credential (`PLATFORM_BOOTSTRAP_CLIENT_ID`/`SECRET`) compromised — the one credential able to create, and thus indirectly control, every `Organization` | ADR-0010 (Organization provisioning): env-var-seeded, never an HTTP-exposed bootstrap path, never shipped in code; structurally separate issuer/JWKS from every tenant (BR-PLATFORM-01) limits *what* a compromise can forge, but does not limit *that* it could create rogue Organizations — compromise response follows `incident-response-platform-client-compromise.md` |
| **S**poofing | A tenant's own `OAuthClient` presents its access token against a management-API endpoint, hoping platform-tier authorization is checked loosely | BR-PLATFORM-02: `/api/v1/admin/*` accepts platform-tier tokens exclusively in v1 — a tenant-scoped token is issued by a structurally different issuer and fails audience/issuer validation before any authorization-scope check even runs |
| **S**poofing / **E**levation of privilege | A tenant `Account` session (established via `/o/{organizationId}/login`) accepted as an authenticated `PlatformAccount` session on `/platform/dashboard` | Live vulnerability, found and fixed same-day (SDE-III review, 2026-08-22): both tiers shared the one app-wide `SecurityContextRepository` bean, and `PlatformDashboardSecurityConfig` checked only `.anyRequest().authenticated()` — any authenticated session, either tier, satisfied it. Confirmed live before the fix (a tenant session reached a fully functional dashboard, including creating a real `Organization`); fixed with an explicit `.hasAuthority("ROLE_PLATFORM_ACCOUNT")` check plus a defense-in-depth authority re-check in `CurrentPlatformAccountResolverBridge`, so a future wiring mistake in the security config alone can't reopen this |
| **S**poofing / **E**levation of privilege | A `PlatformAccount` session (established via `/platform/login`, never touching any Organization's own login page) accepted as the resource owner completing a tenant Organization's own `/o/{organizationId}/oauth2/authorize` request | Live vulnerability, found and fixed same-day (SDE-III review, 2026-08-22), same shared-`SecurityContextRepository` root cause as the row above — but the fix pattern differs: decompiling Spring Authorization Server confirmed its own authorize-endpoint filter reads `SecurityContextHolder` directly and fully handles the request before `authorizeHttpRequests`' own `AuthorizationFilter` ever runs, so a role check there alone would have been a no-op. Fixed with `TenantAccountOnlySecurityContextFilter`, inserted immediately after `SecurityContextHolderFilter`, which resets a wrong-tier authenticated context to empty for that request only — SAS's own converter already treats an empty context exactly like a genuinely anonymous visitor |
| **T**ampering | `ownerPlatformAccountId` on `POST /api/v1/admin/organizations` (or on the session-authenticated dashboard, defense in depth) referencing no real `PlatformAccount` — the migration's own comment claimed this was "enforced at the application layer only," but that layer didn't exist | Found and fixed same-day (SDE-III review, 2026-08-22): `PlatformAccountExistsChecker` (organization-module) rejects `CreateOrganizationCommand.handle()` with `PlatformAccountNotFoundException` before the `Organization` row or its signing key are ever created; the REST path maps this to `404` |

## 5. Lessons from a comparable system's disclosed CVEs (Clerk)

Two real, disclosed vulnerabilities in Clerk (`docs/00-vision/clerk-feature-analysis.md` §5) map directly onto attack surfaces Clavaris shares — added here as concrete threat rows with Clavaris-specific tests, not as abstract cautionary tales.

| Threat | Real-world precedent | Clavaris mitigation / required test |
|---|---|---|
| **E**levation of privilege | CVE-2025-63700: `clerk-js` allowed bypassing the OAuth flow entirely by manipulating the request at the OTP-verification step — a mid-flow step was skippable/reorderable | Every multi-step authentication flow (email verification, MFA challenge once built, social-login linking) must be modeled as an explicit server-side state machine where each step's completion is a precondition checked server-side, never inferred from "the client called the next endpoint" — required test: attempting to call a later step's endpoint directly, skipping the precondition, must fail |
| **E**levation of privilege | A `@clerk/nextjs` SDK bug: passing an authorization parameter (`role`/`permission`) in the same options object as certain other fields (`token`, `unauthorizedUrl`) caused the authorization check to be **silently discarded** rather than evaluated | Authorization checks on the management API must fail closed on any configuration ambiguity — never "if this combination of parameters is present, skip the check silently." Required test: every authorization-check code path has a test asserting it actually runs (not just that the *expected* behavior results) for every documented combination of check parameters, not just the common case |
| **E**levation of privilege | January 2024 `@clerk/nextjs` bug present for over a year before discovery, allowing acting on behalf of another user | A defect class this severe surviving undetected for a year argues for the ArchUnit/ISA-level test discipline already mandated (`test-strategy.md` §2) to explicitly include authorization-check *presence*, not just correctness — a missing check is easy to miss in review, hard to miss in a test that asserts the check exists |

## 6. Known gaps (not yet resolved)

- ~~No incident-response runbook for signing-key compromise~~ — **resolved 2026-08-21**: `incident-response-signing-key-compromise.md`. Written against the system as it actually runs today, not the eventual ADR-0010 §5.2 design — the two currently diverge (no per-tenant rotation endpoint exists yet), and the runbook says so explicitly rather than describing a containment path that doesn't exist.
- ~~No incident-response runbook for `PlatformClient`/bootstrap-credential compromise~~ — **resolved 2026-08-21**: `incident-response-platform-client-compromise.md`. Writing it surfaced a genuinely new gap, not previously tracked: there is no self-service way to revoke or rotate a `PlatformClient` at all today, only raw SQL against production — tracked as TD-SEC-018.
- No formal audit-logging design for the management API (distinct from the auth-event logging already specified in `nfr-quality-attributes.md` §5) — tracked jointly with `BR-ADMIN-01`'s impersonation-audit requirement (`business-rules.md`), since both need the same underlying audit-log mechanism. **This is now a hard blocking dependency, not just a gap**: ADR-0010 §6.2 explicitly requires audit logging to ship *before* tenant self-service `RateLimitPolicy` editing (v1.1) can be enabled — that v1.1 item cannot start until this gap closes. Every platform-tier action (organization creation, key rotation) is exactly the kind of action this audit log must cover first. **Widened 2026-08-22** (SDE-III review of ADR-0012's `PlatformAccount`/Organization-ownership work): "organization creation" here used to mean an *operator's* action, via the REST path only; ADR-0012 made it self-service via `/platform/dashboard`, so the same unaudited gap now covers every registered `PlatformAccount` acting on its own behalf, not a small trusted-operator set — and the same self-service surface has zero rate limiting today (TD-SEC-001), an identical widening not yet reflected as its own row here since it's the same root gap, not a new one.
- MFA absence (backlog per `prd-mvp.md`) means a compromised password alone is sufficient for full account takeover in v1 — accepted risk for v1, explicitly flagged, not silently ignored.
- ~~Spring Authorization Server's multi-tenant JWKS extension (required by ADR-0010 §5) is unvalidated in code~~ — **resolved 2026-08-17**: spike completed with a GO result, see `docs/03-architecture/spikes/0001-spring-authorization-server-multitenancy.md` and ADR-0003's addendum.

These gaps must be closed or explicitly risk-accepted before the mandatory external security review gate.
