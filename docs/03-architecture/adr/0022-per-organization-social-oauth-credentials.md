# ADR-0022: Per-Organization social OAuth credentials (PRODUCTION only)

**Status:** ✅ Aprobado (2026-09-05)

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
