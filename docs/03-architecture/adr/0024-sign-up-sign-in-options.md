# ADR-0024: Sign-up/sign-in options (Clerk parity) — per-Organization control plane

**Status:** 🟡 En revisión (2026-09-07)

## Context

Clerk's own "Sign-up and sign-in options" configuration surface (email verification method,
passwordless email sign-in, username, optional password, "new device sign-ins require extra
verification") is a real, named gap against a mature multi-tenant IdP this codebase competes with
(CLAUDE.md §1). Every one of these is a per-tenant policy choice, not a Clavaris-wide behavior
change — the same shape ADR-0020 (social login) and ADR-0010 §6 (rate-limit capacity) already
established for "one tenant's own decision, structurally isolated from every other tenant's."

**Phone number/SMS is explicitly out of scope for this ADR** — the one item on Clerk's own list
that requires a real, paid, third-party SMS provider (the same category of decision Resend was for
email, ADR-0011). Deferred as a named technical-debt row (`technical-debt-register.md`), not
attempted here, not silently implied by anything this ADR does ship. Nothing below assumes a future
`phone` field beyond leaving the schema and the policy aggregate free to grow one later without
rework.

**`Account.email` stays structurally mandatory.** Making email itself fully optional (Clerk's
"Require email address" = OFF) would mean restructuring `Account`'s own identity model — the field
is non-null and constructor-enforced today — not adding a toggle. Out of scope, named here as a
permanent architectural boundary, not attempted. Email sign-up and email sign-in are themselves
**always on**, the same fixed posture BR-ID-12 already states for password before this ADR (see
Decision 1 below for why that stays true even once password itself becomes optional).

## Decision

### 1. `AccountAuthenticationPolicy` — its own aggregate, `RateLimitPolicy`'s shape, not
   `socialLoginEnabled`'s

A new organization-module aggregate, own table `account_authentication_policies`
(`organization_id` FK, `UNIQUE(organization_id)`, absence of row = defaults that exactly match
pre-ADR-0024 behavior — zero regression for every Organization that never touches this). Nine
fields, richer than `Organization.socialLoginEnabled`'s 2-field shape, so it follows
`RateLimitPolicy`'s own "separate table, own aggregate" precedent instead:

| Field | Default | Meaning |
|---|---|---|
| `emailVerificationRequiredAtSignIn` | `false` | gate sign-in itself on a verified email, not just a non-blocking notice |
| `emailVerificationMethod` | `LINK` | `LINK` \| `CODE` \| `BOTH` — how a verification email proves control |
| `emailCodeSignInEnabled` | `false` | passwordless sign-in via a 6-digit one-time code |
| `emailLinkSignInEnabled` | `false` | passwordless sign-in via a single-use magic link |
| `usernameSignUpEnabled` | `false` | accounts may claim a `Username` at sign-up |
| `usernameRequired` | `false` | only meaningful with `usernameSignUpEnabled` |
| `usernameSignInEnabled` | `false` | only meaningful with `usernameSignUpEnabled` |
| `passwordAtSignUpEnabled` | `true` | matches pre-ADR-0024 mandatory-password sign-up |
| `deviceTrustEnabled` | `false` | step-up challenge for a sign-in from an unrecognized device |

Email/password sign-up and sign-in are **not fields** — deliberately. A field that rejects one of
its two values (an "email sign-up enabled" toggle that can never actually be turned off, per the
`Account.email` boundary above) is a dishonest API shape; it is documented here as permanently on
instead. `SetAccountAuthenticationPolicyForOrganizationService` (operator/`PlatformClient`-only,
new `PlatformScopes.ACCOUNT_AUTHENTICATION_POLICY_WRITE`) validates two cross-field invariants
before persisting:

- `usernameRequired` or `usernameSignInEnabled` without `usernameSignUpEnabled` → rejected
  (`UsernameRequiredWithoutSignUpException`) — a login method a sign-up flow can never produce is
  incoherent.
- `passwordAtSignUpEnabled=false` with neither `emailCodeSignInEnabled` nor
  `emailLinkSignInEnabled` set → rejected (`PasswordOptionalRequiresPasswordlessSignInException`) —
  see Decision 2 for why passwordless sign-up needs one of these as its own completion step.

### 2. Password-optional sign-up reuses passwordless sign-in as its completion step, not a third path

`BR-ID-02`'s "never zero auth methods" invariant is enforced by a `DEFERRABLE INITIALLY DEFERRED`
Postgres constraint trigger (`V20260830110000`) that checks only `password_credentials`/
`social_identities` at commit — a transient `VerificationToken` row from a naive "create with no
password, verify later" design would fail that trigger on every single passwordless registration,
in the very same transaction, before any follow-up request could ever satisfy it. Rather than
touching that trigger (a live security invariant, not something this ADR re-litigates), a
`passwordAtSignUpEnabled=false` sign-up with no password submitted always attaches a real password
credential — a cryptographically random, 32-character, never-surfaced value from the new
`RandomPasswordGenerator` (identity-module), the exact established pattern
`WorkspaceMemberAccountProvisionerBridge.generateRandomPassword()` already uses for provisioned
workspace members. This trivially satisfies the trigger with zero schema change and zero regression
risk. The account's real first sign-in happens via `authenticatewithemailcode`/
`authenticatewithemaillink` (Decision 3) immediately after registration — entering the code (or
clicking the link) both verifies the email and establishes the very first session in one step,
which is why Decision 1's validation requires at least one of those two flags whenever password is
optional: passwordless sign-up needs a passwordless way to actually complete.

### 3. Passwordless email sign-in — two flows, one shared session-establishing factor

`requestemailsignincode`/`authenticatewithemailcode` and `requestemailsigninlink`/
`authenticatewithemaillink` are direct siblings of `requestpasswordreset`/`confirmpasswordreset`
(silent-on-unknown-email anti-enumeration, `DEVELOPMENT`-environment bypass). Both call the same
new `AuthenticatedSessionEstablisher.establishViaOneTimeEmailProof(...)` — a single-use value
proven once is the same OIDC `amr=["otp"]` factor regardless of which of the two delivery
mechanisms carried it, so one establish method serves both, using Spring Security's real
`FactorGrantedAuthority.OTT_AUTHORITY` (`"FACTOR_OTT"`) plus a `SimpleGrantedAuthority("AMR_OTP")`
— RFC 8176's own registered `otp` value, more standard than the provider-name-minted AMR values
social login already uses.

**The magic link is deliberately not auto-completed on a bare `GET`.** Email
scanners/prefetchers (corporate security gateways, some clients' own "safe link" rewriting) fetch a
link's `GET` automatically before a human ever sees it — auto-completing on `GET` would silently
burn a genuinely single-use sign-in token before its intended recipient could use it. The emailed
link instead opens a same-origin confirmation page with a CSRF-protected `POST` button; only that
deliberate, human-initiated `POST` actually authenticates.

A 6-digit code is ≈1,000,000 combinations, unlike an unguessable 256-bit link token — brute force
is a real new concern the link path doesn't have. Mitigated at the HTTP layer, reusing the existing
`AntiAbuseRateLimitingFilter` infrastructure (not a new in-process counter — no stable identifier
exists pre-lookup for an anti-enumeration-collapsed failure), one new rule
`"login-email-code-confirm:email"` keyed by the submitted email field.

### 4. Username — a dedicated route, not a merged login field

New `Username` value object (identity-module), nullable `accounts.username` column with a partial
unique index (`WHERE username IS NOT NULL`) scoped `(organization_id, username)` — additive,
no backfill required. `authenticatewithusername` is a structural twin of
`authenticatewithpassword`, looking up by `(organizationId, username)` instead of email, reusing
`establish(...)` unchanged (still `amr=["pwd"]` — the factor is still a password, only the
identifier differs). Username sign-in gets its **own dedicated route**
(`UsernameSignInController`, `/o/{organizationId}/login/username`) rather than merging into
`LoginController`'s single field: the two controllers' form base classes diverge in ways a shared
`@Email`-constrained field can't absorb (see Consequences), and it matches the "one method, one
route" pattern already established for the two passwordless email flows in Decision 3.

### 5. Device Trust — a step-up challenge, built on the existing device-recognition primitive

`KnownDevice`/`KnownDeviceRepository` (TD-SEC-033) already distinguish recognized from unrecognized
devices via an opaque, unforgeable cookie token — purely informational today (notify by email,
never block). `deviceTrustEnabled=true` turns that same recognition check into a gate: each of the
four primary-factor controllers (email+password, username, email-code, email-link) checks device
recognition **before** calling its `AuthenticatedSessionEstablisher` method, via a shared static
`DeviceTrustGate.intercept(...)` helper (same stateless-utility shape as `DeviceCookie`). An
unrecognized device on a policy-enabled Organization pauses the login — the pending
`(accountId, primary-factor-used, organizationId)` is held in the unauthenticated `HttpSession`
(BR-ORG-02 defense-in-depth: the Organization is re-checked on resume, not trusted from the URL
alone) — and issues a new `VerificationTokenType.DEVICE_TRUST_CHALLENGE` code via the same
`EmailOneTimeCode`/rate-limited mechanism Decision 3 established. Only on a correct code does the
originally deferred `establish`/`establishViaOneTimeEmailProof` call actually run, followed by the
same `RecordAccountLoginDeviceService.handle(...)` step every controller already performed.

**Named limitation, not silently glossed over:** a device recognized before `deviceTrustEnabled`
was turned on for an Organization stays recognized — this check only ever challenges a genuinely
*new* device going forward, never retroactively challenges an already-trusted one the moment the
policy flips on. Recorded in `technical-debt-register.md`, not treated as solved.

**Second named limitation:** `AuthenticationContextClaimsCustomizer`'s `amr` claim reflects only
the primary factor after a device-trust step-up in v1 — true step-up MFA composition
(`amr=["pwd","otp"]`) is deferred, also recorded as technical debt, not faked as if the claim were
already accurate.

## Consequences

- **Positive:** closes Clerk's entire "sign-up/sign-in options" surface except phone/SMS (explicit
  scope cut, not an oversight), with every new capability opt-in and additive — an Organization that
  never touches `AccountAuthenticationPolicy` sees byte-for-byte the same behavior as before this
  ADR.
- **Positive:** password-optional sign-up needed zero changes to the BR-ID-02 database trigger — a
  real design conflict was found and resolved by reusing an already-shipped pattern
  (`RandomPasswordGenerator`), not by weakening a live security invariant to make a new feature fit.
- **Negative:** `RegisterAccountForm` no longer extends the shared `EmailPasswordConfirmationForm`
  (`RegisterPlatformAccountForm` still does) — `password`/`confirmPassword` needed to become
  policy-driven optional (`@Size` without `@NotBlank`, since JSR-380 treats `null` as valid for
  `@Size`) while the platform tier's own registration must keep its hard requirement. A real, if
  small, divergence between the two form hierarchies going forward.
- **Negative:** a genuine new brute-force surface (6-digit codes, both for email sign-in and device
  trust) that the pre-ADR-0024 system didn't have — mitigated via HTTP-layer rate limiting, not a
  design that eliminates the exposure outright.
- **Negative:** the two named Device Trust limitations above (no retroactive challenge on
  already-trusted devices, `amr` not reflecting true step-up composition) are real, acknowledged
  gaps versus a from-scratch MFA design — accepted for v1 given Device Trust's own scope (a
  new-device notice made blocking, not a general-purpose second factor).

## Alternatives considered

- **Model email/password sign-up and sign-in as togglable policy fields, matching every other
  field's shape** — rejected: `Account.email`'s non-null boundary means "email sign-up disabled"
  can never actually be honored, and a field that structurally cannot be turned off is a worse API
  than no field at all.
- **A per-account failed-attempt counter for code brute-force throttling, instead of the HTTP-layer
  filter** — rejected: several of the code-based flows (passwordless sign-in, in particular) have no
  stable pre-lookup identifier to key an in-process counter by, without itself becoming an
  enumeration oracle.
- **Auto-complete the magic link on its bare `GET`** — rejected outright: a well-documented
  magic-link pitfall (email scanner/prefetch burning a single-use token before the real recipient
  acts), not a hypothetical risk.
- **Retrofit `LoginController`'s single field to detect username-vs-email shape and route
  internally** — considered, rejected once `LoginForm`'s shared `@Email` validation with
  `PlatformLoginForm` turned out to conflict with a username-shaped value; a dedicated route was
  simpler and matched existing precedent better than forking the shared form hierarchy further.
