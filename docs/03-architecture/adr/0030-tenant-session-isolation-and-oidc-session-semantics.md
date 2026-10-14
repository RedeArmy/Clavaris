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
Core 3.1.2.1/3.1.2.6); the actions that change the user's state (ending the session, forgetting a
consent) only happen on a navigation (`Sec-Fetch-Dest`), never for a subresource another site
requests. Errors go to a `redirect_uri` only when it is registered for the client of that
Organization (exact match). Logout without `id_token_hint` shows a confirmation page protected by a
single-use token and redirects only to a registered `post_logout_redirect_uri`.

## Consequences

- Two Organizations in one browser are independent sessions; a browser holds one cookie
  (~100 bytes) per Organization signed in to.
- Two social logins pending at once in two Organizations complete in their own tenant.
- Known limits: a frame (`Sec-Fetch-Dest: iframe`) from another site can still trigger
  `prompt=login`/`prompt=consent` for a registered client (the embedded modal of ADR-0009 needs
  frames); the cost is a forced re-login. The re-authentication note expires after ten minutes and
  a second authorize request in the same session discards it, which can ask for a second login.
