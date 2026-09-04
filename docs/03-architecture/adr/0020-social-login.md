# ADR-0020: Social login (Google/GitHub) — account linking, platform-tier scope, per-tenant policy

**Status:** ✅ Aprobado (2026-08-28)

## Context

Social login is the one v1 capability row with zero code behind it (`roadmap-and-release-plan.md`
§2). `prd-mvp.md` §5 names a real open question that must resolve before any of it ships, "not
after": whether account linking by verified email alone is safe, or needs an explicit confirmation
step. `domain-model.md` §2's own note already leans toward the latter but never formally decided.
Four more real decisions were needed before implementation could start, none previously written
down anywhere: whether Clavaris's own `PlatformAccount` tier keeps password auth alongside social,
where a tenant's "allow social login, and with which providers" choice lives, who owns the Google/
GitHub OAuth app credentials, and whether Microsoft/Active Directory belong in this same effort.

## Decision 1 — Account linking: explicit confirmation, not automatic by verified email

**Explicit confirmation.** A social login whose verified email matches an existing `Account`'s
email, where that `Account` was created by a different method (password, or a different provider),
does **not** auto-link. It creates a pending link and requires the account holder to confirm it
through the email address of record (a confirmation link, same delivery mechanism as
`VerificationToken`) before the `SocialIdentity` row is actually attached.

**Why, concretely**: `RegisterAccountController`'s own self-service registration is ungated —
anyone can register any email today, verified or not, and `email_verified_at` is set later, not at
registration time (`business-rules.md`, `AuthenticateWithPasswordService`'s own status check gates
*login*, not registration). That means an attacker can pre-register an email they don't control,
never verify it, and simply wait — if social linking trusted "verified email matches" alone, the
real account holder's first legitimate Google/GitHub login would silently attach to the attacker's
pre-registered `Account`, handing the attacker a live session on an identity they never verified
owning. Automatic linking is only as safe as "no unverified account can ever exist first," which
this codebase does not guarantee and was never going to be asked to guarantee just to make this one
feature simpler.

## Decision 2 — Platform tier: social login added alongside password, both permanently available

`PlatformAccount` (Clavaris's own operators, `RegisterPlatformAccountController`/
`PlatformLoginController`, already shipped) gets Google/GitHub as an **additional**, permanent
sign-up/sign-in path — existing password registration and login stay exactly as they are today,
for both new and existing accounts. Nothing shipped is deprecated, closed, or scheduled for
removal; `PlatformPasswordCredential` and `SocialIdentity`-for-`PlatformAccount` are two
permanently coexisting authentication methods, same BR-ID-02 "multiple methods, never zero"
shape `Account` already has, extended to this tier.

Rejected the two narrower alternatives evaluated: full password deprecation (removing shipped,
tested, currently-working infrastructure with no deadline forcing it) and "new sign-ups
social-only" (closing a working registration path for a population — future operators — with no
stated reason to restrict it). Explicit choice, not the default: adding social login here is
strictly additive engineering work, not a removal, which is also the lower-risk path for
infrastructure this project's own security posture treats as high-stakes (the platform tier gates
`Organization` creation itself).

## Decision 3 — Per-tenant social-login policy: `Organization`-level, additive only, email never gated

**Email/password for a tenant's own `Account` population is permanently, unconditionally
available — no `Organization` setting can ever disable it.** `RegisterAccountController`/
`LoginController` stay exactly as they are today, untouched by anything this ADR adds; there is no
code path anywhere that checks a tenant's social policy before allowing email registration or
login. A tenant's choice — whether to *additionally* offer social login, and if so which
providers — lives on `Organization`, not on individual `OAuthClient` rows: two new columns,
`social_login_enabled boolean NOT NULL DEFAULT false` and `allowed_social_providers text` (JSON
array, e.g. `["GOOGLE","GITHUB"]` — same "`text` JSON array in v1, normalize later if per-value
querying is ever needed" convention `oauth_clients.allowed_scopes`/`redirect_uris` already
established, TD-ARCH-003). Defaults closed (`false`, empty) — an Organization opts in to *social*
specifically; email is never something to opt into, it is simply always there.

Symmetric with Decision 2's own platform-tier shape, deliberately: both tiers only ever *add*
sign-in methods, never remove one that already exists — the difference is only in who controls the
addition (Clavaris itself decides its own tier is Google+GitHub+email, always all three; a tenant
decides for itself, per `Organization`, whether social is on top of the email that's always there).

`LoginController` is already scoped `/o/{organizationId}/login` (one hosted login page per
Organization, not per `OAuthClient`) — Organization-level policy is the natural fit for a page that
already only ever knows which Organization it's rendering for, not which specific client app
initiated the flow. An `OAuthClient`-level policy would need that page to also resolve and branch
on the specific client, a real, unrequested complexity increase with no named use case driving it.

## Decision 4 — OAuth app ownership: Clavaris-owned shared app, not bring-your-own per tenant

Clavaris registers exactly one Google OAuth Client and one GitHub OAuth App, shared across every
Organization that enables social login — the standard multi-tenant IdP shape (Auth0, Clerk, and
every comparable product `docs/00-vision/clerk-feature-analysis.md` already studies work this way).
A new tenant enabling social login is a two-field database flip (`social_login_enabled = true`,
`allowed_social_providers`), zero external setup — directly serving CLAUDE.md §2's own "integrate
via standard OIDC client libraries in under a day" success metric. The real cost: Google's/GitHub's
own consent screen shows Clavaris's name/logo, not the tenant's — an explicit, accepted trade-off,
not an oversight; a tenant wanting its own branded consent screen is exactly the shape of ask
ADR-0009 (embedded/branded login) already exists to eventually serve, not something this decision
should try to half-solve by making every tenant register its own OAuth app today.

## Decision 5 — Microsoft and Active Directory: Microsoft deferred as a cheap future extension of this same design; Active Directory is a separate, larger initiative, also deferred

Evaluated as asked, not silently skipped:

- **Microsoft (Entra ID / Azure AD v2.0 endpoint)** is architecturally the same shape as Google and
  GitHub — a standard OAuth2/OIDC authorization-code provider. Everything this ADR designs
  (`SocialProvider` as an extensible enum, one `OAuth2LoginConfigurer` registration per provider,
  the same linking/confirmation flow) already accommodates a third provider with no redesign —
  adding it later is genuinely additive, not a rewrite. Deferred out of this implementation only
  because CLAUDE.md §4's own locked v1 scope names "Google, GitHub — v1 scope" specifically;
  adding a third provider mid-implementation is scope growth on a feature not yet shipped once,
  not a decision this ADR should make unilaterally. Tracked as `TD-FUT-022`.
- **Active Directory / institutional email** is a structurally different problem, not a third row
  in the same `SocialProvider` enum. "Institutional email" login most commonly means either (a) a
  tenant's own Microsoft/Google Workspace account — already covered by Microsoft/Google OAuth once
  Decision 5's first bullet ships, nothing extra needed — or (b) real enterprise identity
  federation (SAML 2.0, or OIDC federation against a tenant's own on-prem AD/Entra ID tenant, or an
  LDAP bind) — a different protocol stack, typically its own SP (Service Provider) implementation,
  per-tenant IdP metadata configuration, and its own real security review surface (SAML in
  particular has a well-documented history of subtle signature-validation vulnerabilities across
  the industry — not something to bolt onto a social-login feature as an afterthought). Genuinely
  out of scope for this ADR and for v1 — tracked as `TD-FUT-023`, its own future initiative needing
  its own dedicated design pass (and likely its own ADR) the day a real consumer asks for it, not
  built speculatively now.

## Consequences

- **Positive:** the linking-confirmation decision closes a real, previously-open security question
  before any code exists to get it wrong — the safer default costs one extra UX step, not a
  redesign later.
- **Positive:** Organization-level policy and a Clavaris-owned shared OAuth app together mean a
  tenant's entire social-login setup is two database columns, no external registration step —
  directly serving this project's own fast-integration goal.
- **Positive:** Microsoft is a named, bounded, low-cost future extension, not an open question —
  the day it's requested, this design doesn't need to change shape to add it.
- **Negative:** the confirmation-link step is a real, deliberate UX cost the "automatic" alternative
  would have avoided — accepted as the correct trade for closing the account-takeover scenario
  Decision 1 names.
- **Negative:** every tenant's social consent screen shows Clavaris's own branding, not the
  tenant's — accepted, with ADR-0009 named as the eventual, deliberate path for tenants that need
  their own branded experience, not something this ADR tries to solve halfway.
- **Negative:** `PlatformAccount` now has two permanently coexisting auth methods to maintain
  (password and social) rather than one — accepted as the lower-risk, strictly-additive path;
  the same BR-ID-02 "multiple methods" shape `Account` already carries, not a new kind of
  complexity this codebase hasn't already modeled once.

## Alternatives considered

See Decisions 1–5 above — automatic email-based linking, full platform-tier password deprecation,
per-`OAuthClient` policy, bring-your-own OAuth apps, and folding Microsoft/AD into this same
implementation were each evaluated on their actual merits, not assumed away.

## Addendum — Decision 4 narrowly amended for PRODUCTION Organizations (2026-09-05, ADR-0022)

Decision 4's shared-app default is **still correct for the common case** and remains unchanged for
`DEVELOPMENT` Organizations and the platform tier: zero-setup, two-database-field opt-in, no
external registration step. What changed: a `PRODUCTION` Organization may now additionally bring its
own Google/GitHub OAuth app credentials, opt-in and per-provider, on top of Decision 3's existing
enabled/allowlist gate — see ADR-0022 for the full design. This is a narrow amendment, not a reversal
of Decision 4's own reasoning; see ADR-0022 for why "eventually via ADR-0009" (this ADR's own
original framing) turned out to be the wrong vehicle: ADR-0009's branding only ever re-skins
Clavaris's own hosted login/consent page, it can never touch Google's/GitHub's own consent screen —
only bringing your own OAuth app changes what those providers themselves display.
