# ADR-0022: Per-Organization social OAuth credentials (PRODUCTION only)

**Status:** ✅ Approved (2026-09-05)

## Context

ADR-0020 Decision 4 (2026-08-28) deliberately chose one Clavaris-owned Google app and one
Clavaris-owned GitHub app, shared across every Organization that enables social login — evaluated
"bring-your-own OAuth apps per tenant" as an alternative and explicitly rejected it, citing
zero-setup integration as the deciding factor and naming ADR-0009 (embedded/branded login) as "the
eventual, deliberate path for tenants that need their own branded experience."

That framing has a real gap: ADR-0009's branding (`ClientBranding`, iframe presentation, custom
domain) only ever re-skins **Clavaris's own** hosted login/consent page. It can never change what
**Google's or GitHub's own** consent screen shows — that screen is rendered by the provider itself,
against whichever OAuth app's `client_id` Clavaris authenticated with, and always displays that
app's own registered name/logo. A tenant whose end users see "Sign in to Acme Corp via Google" (not
"via Clavaris") needs Clavaris to actually authenticate against *their own* Google Cloud Console
project — no amount of re-skinning Clavaris's own pages gets there. This ADR closes that specific
gap, narrowly, rather than leaving it as a permanent unaddressed limitation of Decision 4.

## Decision

A `PRODUCTION`-environment Organization may register its own Google and/or GitHub OAuth app
credentials (`client_id`/`client_secret`), used in place of the shared Clavaris app for that
provider's login flow. Four scoping choices, each deliberate:

1. **`PRODUCTION` only, never `DEVELOPMENT`, never the platform tier.** A sandbox Organization has
   no end users whose consent-screen branding matters; the shared app remains correct and
   sufficient there, same reasoning already established for the verification-email bypass (BR-ID-15)
   and the lower rate-limit default (BR-ORG-08) — every `DEVELOPMENT`-scoped policy difference this
   codebase has built so far narrows capability/cost for the sandbox, never widens it.
2. **Additive to Decision 3's existing gate, never a bypass of it.** `social_login_enabled`/
   `allowed_social_providers` (ADR-0020 Decision 3) still governs *whether* social login is offered
   at all for a given provider. Bringing your own credentials only changes *which app's* credentials
   are used once that gate is already open — setting credentials for a provider the Organization
   hasn't allowed is rejected outright, not silently ignored.
3. **Per-provider independent.** An Organization may bring its own Google app while still using the
   shared GitHub app, or vice versa — no "all or nothing" requirement.
4. **Reversible encryption at rest, own dedicated key.** `client_secret` must be presented outbound
   to Google/GitHub in cleartext on every token exchange — the opposite shape from `OAuthClient
   .clientSecretHash`'s one-way hash. Reuses the AES-256-GCM pattern `AesGcmWebhookSigningSecretCipher`
   (webhook-module, ADR-0007) already established, with its own dedicated env-seeded key
   (`SOCIAL_CREDENTIAL_ENCRYPTION_KEY`) — this codebase's own convention is that every at-rest secret
   gets an independently-rotatable key, never a reused one.

Mechanically: `spring.security.oauth2.client.registration.*`'s existing shared registrations remain
the fallback for every Organization that hasn't opted in (`app`'s `TenantAwareClientRegistrationResolver`,
resolved per-request from the same `HttpSession` attribute `SocialLoginRedirectController` already
sets to carry "which Organization initiated this login"). The registration-id scheme (`google`/
`github`) is unchanged — no new URL shape, no new callback path.

## Consequences

- **Positive:** closes the real gap Decision 4 itself named without silently waiting for ADR-0009 to
  half-solve it (which, per Context above, it structurally never could).
- **Positive:** the shared-app default — Decision 4's own core value — is completely undisturbed for
  every Organization that doesn't opt in, and structurally unavailable to `DEVELOPMENT`/platform
  tier, so the "zero-setup integration" success metric (CLAUDE.md §2) is unaffected for the common
  case.
- **Negative:** a real new secret class to protect — mitigated by reusing an already-vetted
  encryption pattern rather than inventing one, but the same acknowledged gap
  `AesGcmWebhookSigningSecretCipher` already carries (no re-encryption-on-key-rotation story) applies
  here too, tracked as the same open item, not silently duplicated as if it were new.
- **Negative:** an Organization that misconfigures its own Google/GitHub app (wrong redirect URI
  registered on Google's/GitHub's own console, since the physical callback URL stays Clavaris's
  shared one regardless of whose credentials are in play) breaks its own social login until fixed —
  an operational risk this ADR accepts as inherent to "bring your own," not something Clavaris can
  validate ahead of time.

## Alternatives considered

- **Leave it to ADR-0009 as originally framed** — rejected once the branding-scope gap above was
  identified: ADR-0009 alone can never solve this, regardless of when it ships.
- **Available to every environment, not just `PRODUCTION`** — rejected: no sandbox Organization has
  end users whose consent-screen branding is a real concern, same reasoning every other
  environment-scoped policy in this codebase already applies.
- **A single shared secret-encryption key across every at-rest secret in the system** — rejected,
  inconsistent with this codebase's own established "one dedicated key per secret class" convention
  (`.env.example`'s own commentary on every existing key already states this explicitly).

## Addendum — PRODUCTION must bring its own credentials; the "Connections" page (2026-10-11)

The shared Clavaris app was meant as the zero-setup default, and for `DEVELOPMENT` it still is. For a
`PRODUCTION` Organization the fallback turned out to defeat the purpose of this ADR: people on a
production application would see "Clavaris" on Google's or GitHub's own consent screen whenever an
Organization had enabled a provider and not (yet) brought its own app. Clerk and Auth0 draw the same
line, development keys for testing and your own keys for production. So, amending Decisions 1 and 2:

1. **A `PRODUCTION` Organization signs in only through its own credentials.** It cannot enable a
   provider it has none for (`SocialCredentialsRequiredException` on the policy endpoint,
   `CredentialsRequiredException` on the dashboard), and the login pages and the sign-in re-check do
   not offer such a provider (`OrganizationSocialLoginPolicyProviderBridge`). Removing a provider's
   credentials switches that provider off in the same transaction. There is no fallback to the shared
   app for `PRODUCTION` any more.
2. **`DEVELOPMENT` is unchanged**: always the shared app, no credentials to bring (still refused if
   given), so nothing about trying social login out got harder.
3. **Credentials can be set before the provider is enabled.** The old guard (the provider had to be
   enabled first) is gone: it made "enable needs credentials, credentials need enabled" impossible
   to satisfy. Enabling is still what the policy gate decides, and it is what now checks credentials.

**The dashboard.** Configure gets a "Connections" page (`PlatformSocialConnectionsController`): a list
of providers with their status (used for sign-in, setup required, disabled) and one page per provider
with the switch, the credentials, the redirect URI to register with the provider (the shared callback
`{base}/login/oauth2/code/{provider}`, same whoever's credentials are used), and a danger zone that
removes the credentials. `SaveSocialConnectionService` saves the three together in one transaction,
writes only what changed and audits each change, never with the secret. A blank client id or secret
keeps the saved one, because a saved secret is never shown again.

**Two per-connection options** (new table `organization_social_connection_settings`, one optional row
per Organization and provider; no row means the behaviour from before they existed):

- *Block email subaddresses*: refuses a social sign-in whose email has `+`, `=` or `#` in its local
  part, which can otherwise get around a provider's own organisation controls. Checked on every
  sign-in, returning accounts included, so turning it on also stops those that already used one. It is
  a method of `OrganizationSocialLoginPolicyProvider` (default `false`), checked by
  `AuthenticateWithSocialProviderService`, the same place the provider-allowed rule is re-verified.
- *Always show account selector*: adds `prompt=select_account` to the redirect to the provider
  (`AccountSelectorAuthorizationRequestResolver`), only for a tenant login that asked for it.

**Consequences.**

- An Organization that was `PRODUCTION` with a provider enabled and no credentials stops offering that
  provider until it saves some. It is not lost: the page shows it as "Setup required".
- The REST admin API changes in two places: `PUT .../social-login-policy` answers 409 for a `PRODUCTION`
  provider with no credentials, and `PUT .../social-credentials/{provider}` no longer answers 409 for
  a provider that is not enabled yet.
- Scopes are not editable: Clavaris asks for the minimum each provider needs for sign-in.
