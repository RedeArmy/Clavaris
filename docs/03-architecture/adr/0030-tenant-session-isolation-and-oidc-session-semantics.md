# ADR-0030: Per-tenant session cookies, tenant-tagged social login, persisted consent and a stable `auth_time`/`sid`

**Status:** ✅ Approved (2026-10-14)

## Context

An OIDC conformance audit and the review that followed it (branch `fix/oidc-email-claims`) found
that the hosted sign-in shared state in ways the protocol does not allow:

1. Every tenant used one session cookie, `SESSION` (path `/`). Signing in to Organization B in the
   same browser reused Organization A's `HttpSession` and overwrote its `SecurityContext`, and B's
   logout ended A. The platform tier was already separated from tenants (`PLATFORM_SESSION`, fixed
   2026-10-10); tenants from each other were not.
2. The social-login callback (`/login/oauth2/code/{provider}`) is one flat URL that names no
   tenant, so the cookie to read could not be told from the request.
3. The OAuth consent lived in memory: it was asked again after every restart and was not shared
   between instances.
4. A refreshed ID token reported `auth_time` from `Session.createdAt` (when the code was
   exchanged), not when the user authenticated, and a different `sid` than the first one.

## Decision

### 1. One session cookie per Organization

`AdaptiveSameSiteSessionCookieSerializer` names the cookie `SESSION_<organizationId without
dashes>` for `/o/{organizationId}/...` (this also covers a verified custom domain, which is
rewritten to that path before the session filter runs). The platform tier keeps `PLATFORM_SESSION`
for `/platform/**`. `SameSite` stays adaptive (`None; Secure` over HTTPS, ADR-0009).

A cookie `Path=/o/{id}` was rejected: Spring Authorization Server fixes `/oauth2/consent` as a flat
URL, so that cookie would never reach it. A context per tenant inside one session was rejected: it
breaks the Redis index by principal that logout and revocation depend on.

Flat paths resolve the tenant like this: `/oauth2/consent` from its `client_id` (the page already
does), the social-login round trip from a tag (§2), anything else stays on `SESSION`. Sessions
created before this change live under `SESSION`, are no longer read on tenant paths, and users sign
in once more (a rolling deploy therefore shows that one re-login to users routed between old and
new instances).

### 2. The tenant travels with the social login

The entry link adds `clavaris_tenant=<tag>` (the Organization id without dashes, or `platform`) to
`/oauth2/authorization/{provider}`; `AccountSelectorAuthorizationRequestResolver` prefixes it to the
OAuth `state` (`<tag>.<random>`), which the provider echoes back. The random part, which is what
Spring Security validates, is unchanged. A missing or malformed tag falls back to `SESSION`; a
forged tag can only choose another cookie of the same browser, and the state check then fails. The
platform's own social login now uses `PLATFORM_SESSION`.

### 3. Consent in Postgres

`oauth2_authorization_consent` (Spring Authorization Server's own schema, migration
`V20261012130100`) with `JdbcOAuth2AuthorizationConsentService`. It holds the account id and scope
names only. It is deleted with its client or Organization; deleting a single Account leaves its
rows (inert: the id is never reused).

### 4. `auth_time` and `sid` come from the Session

`sessions.authenticated_at` (`V20261012130000`, backfilled from `created_at`) holds the latest
authentication factor's instant when the code is exchanged, so a refreshed ID token repeats the
first token's `auth_time`; `prompt=login`/`max_age` re-authentication produces a new one.
`sessions.oidc_sid` (`V20261014120000`, nullable) holds the `sid` Spring Authorization Server put on
the first ID token (copied by `OidcSessionIdRecorder`), because logout compares the hint's `sid`
with the browser session: before this, an RP that sent its latest (refreshed) ID token as the hint
got a 400. A Session that predates the column keeps reporting its own id as before.

### 5. `prompt`, `max_age` and logout

`OidcAuthorizationRequestParamsFilter` implements `prompt=none|login|consent` and `max_age` (OIDC
Core 3.1.2.1/3.1.2.6). Errors go to a `redirect_uri` only when it is registered for the client of
that Organization (exact match).

**A request never destroys the End-User's state.** The first version dropped the session on
`prompt=login` and deleted the stored consent on `prompt=consent`, guarded only by `Sec-Fetch-Dest`
(a navigation, not an image). That is not enough: the CSP `frame-ancestors` stops another site's
iframe from being *shown*, but the request is still made with the visitor's cookie, so a hostile
page could log a visitor out or wipe their consents. Now:

- `prompt=login` / an expired `max_age`: the request is saved, a note is left and the hosted login
  is shown. The current authentication stays until a new login replaces it (the login page does not
  skip itself for a signed-in visitor), and the resumed request passes once a factor newer than the
  note exists. From another site the worst case is a login page the visitor never sees.
- `prompt=consent`: the filter only marks the request; `RequestScopedConsentService` makes the
  authorization endpoint see no stored consent for that request alone. Nothing is deleted, an
  approval replaces the stored consent, and an abandoned screen leaves the old one in place (the
  next plain authorize is granted).
- Residual, accepted: a request from another site can still save an authorize request and leave a
  note in the visitor's session (saving a request to resume after login is what Spring Security
  does for any unauthenticated authorize). It is bounded and expires (below), and resuming it
  only reaches a registered redirect URI of a registered client with the visitor's own login.

**Notes per request.** `ReauthenticationMarker` keeps one note per request (its sorted parameters,
without Spring's `continue`), at most five, each valid for ten minutes, used up when it counts. A
second authorize request while the first waits for its login no longer discards the first's note, so
the resumed request needs only one login. A note counts only for a login made after it was left.

**Every login method counts.** The new authentication instant is the latest
`FactorGrantedAuthority.issuedAt`; password, social (`AUTHORIZATION_CODE`), magic link (`OTT`) and
passkey (`WEBAUTHN`) sign-ins each put one on the context, and a device-trust step-up adds another
(`OidcRequestParametersTest`).

**Logout without `id_token_hint`.** A confirmation page protected by a single-use token kept in the
End-User's own session, redirecting only to a registered `post_logout_redirect_uri`. When nobody is
signed in on that Organization there is nothing to confirm or end: the request completes at once
(registered redirect, else the "signed out" page) and **no session is created for it**; before, an
anonymous request opened an empty session just to hold a token. A stateless signed token was
considered and not needed: the token is only ever issued to a visitor who already has a session.

### 6. `nonce` on a refreshed ID token

OIDC Core 12.2: an ID token issued by a refresh request SHOULD NOT carry `nonce`, and if it does
its value MUST equal the original. Clavaris's refreshed ID token carries none, which conforms; the
other claims that must be repeated (`iss`, `sub`, `aud`, `auth_time`, `sid`) are.

### 7. Migrations on tables that already have rows

`V20261012130000` (`sessions.authenticated_at`) adds a nullable column, backfills it with `UPDATE ...
SET authenticated_at = created_at` and then makes it `NOT NULL DEFAULT now()`. The `UPDATE` rewrites
every row of `sessions`, unlike adding the column with a default (metadata only on PostgreSQL 16:
`now()` is stable, so the value is stored once). Measured on PostgreSQL 16 with a temporary table of
one million rows: the backfill takes about 1.4 s, `SET NOT NULL` another 0.2 s, and the table is
twice the size until vacuumed (355 MB against 182 MB); the `ACCESS EXCLUSIVE` lock is held for the
whole migration transaction. The table is small in practice (35 rows in the local database), and the
migrations are not on `master` or deployed by any workflow, but the local development database has
them applied, so editing the file would break Flyway's checksum validation there. They are left as
they are and the operational note is in the deployment runbook (section 10).

## Consequences

- Two Organizations in one browser are independent sessions; a browser holds one cookie
  (~100 bytes) per Organization signed in to.
- Two social logins pending at once in two Organizations complete in their own tenant.
- A request from another site cannot end a session or forget a consent (see 5).
- Consents of deleted Accounts remain as inert rows: deleting an Account shares its token revoker
  with refresh-token reuse and password reset, where wiping consents would force a new consent
  screen at every login.
