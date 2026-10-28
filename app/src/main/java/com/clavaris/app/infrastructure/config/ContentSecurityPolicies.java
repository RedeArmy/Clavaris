package com.clavaris.app.infrastructure.config;

import java.util.regex.Pattern;

/**
 * The Content-Security-Policy strings and the path patterns that pick between them (TD-ARCH-042).
 * Split out of {@link ContentSecurityPolicyHeaderWriter}, which keeps the logic that chooses a
 * policy for a request and relaxes {@code frame-ancestors}/{@code form-action} for it; every
 * comment moved with the constant it explains.
 */
// PMD.AvoidDuplicateLiterals: the policies repeat the directive names on purpose, so each reads as
// one complete policy.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class ContentSecurityPolicies {

  // font-src 'self' on every policy below: clavaris.css self-hosts Geist (/fonts/*.woff2). With
  // font-src 'none' the browser blocked both files, so the whole UI silently fell back to the
  // system font. Same-origin fonts only — never a font CDN.
  /* package */ static final String STRICT_POLICY =
      "default-src 'self'; script-src 'none'; style-src 'self'; img-src 'self'; "
          + "font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  // Every other page the consuming application's people see (sign-up, password reset, consent):
  // STRICT_POLICY except script-src 'self', because consumer-head loads appearance.js on all of
  // them. With script-src 'none' the app's page background was blocked on every screen but login,
  // passkeys and profile, and vanished on the next page of the flow. Same-origin files only; still
  // no inline script, connect-src or framing.
  /* package */ static final String CONSUMER_POLICY =
      STRICT_POLICY.replace("script-src 'none'", "script-src 'self'");

  // Matches only ConsentController's own flat, org-agnostic GET — see CONSENT_PATH_PATTERN's own
  // comment in OrganizationAuthorizationServerConfig for why this is not "/o/*/oauth2/consent".
  // Never the platform tier (client_credentials only, BR-PLATFORM-01, no interactive consent to
  // render).
  /* package */ static final Pattern CONSENT_PAGE_PATH = Pattern.compile("^/oauth2/consent$");

  // TD-SEC-050: the one point in the whole flow that reliably sees display=modal on an
  // authenticated (or about-to-authenticate) request before SAS's own sendAuthorizationConsent
  // drops it on its internal redirect to CONSENT_PAGE_PATH above — see this class's own Javadoc
  // addendum for the full flow this closes.
  /* package */ static final Pattern AUTHORIZE_PATH =
      Pattern.compile("^/o/[^/]+/oauth2/authorize$");

  // TD-SEC-009 addendum, see this class's own Javadoc: the one project-owned template that now
  // loads a real, same-origin script.
  //
  // connect-src 'self', not 'none' (TD-FUT-034, same DASHBOARD_PAGE_POLICY bug class found live
  // 2026-09-16, now repeated here deliberately): webauthn-login.js's own "Sign in with a passkey"
  // button issues fetch() calls to /login/webauthn/start and /login/webauthn/finish — governed by
  // connect-src, never script-src. script-src 'self' only permits loading the script file itself.
  /* package */ static final String LOGIN_PAGE_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
          + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  // Matches only LoginController's own GET/POST /o/{organizationId}/login — never
  // /o/*/login/social/** (SocialLoginConfig's plain links, nothing to double-submit) or the
  // platform tier's own login template (a different page, no such script).
  /* package */ static final Pattern LOGIN_PAGE_PATH = Pattern.compile("^/o/[^/]+/login$");

  // ADR-0025: same relaxation, own named policy — see this class's own Javadoc for why not reused.
  //
  // connect-src 'self', not 'none' (SDE-III review, 2026-09-16 — real bug, found live): every
  // mutation and every paginated list on this whole dashboard (register/deactivate/rotate-secret,
  // Organizations, Workspaces, OAuth Clients, Secret Keys, ...) is an HTMX hx-post/hx-get, which
  // issues its request via fetch()/XHR — governed by CSP's connect-src, never script-src.
  // script-src 'self' only permits loading /js/htmx.min.js itself, not the requests that script
  // makes. With connect-src 'none' (copied from STRICT_POLICY/LOGIN_PAGE_POLICY, neither of which
  // makes any fetch call at all), the browser silently blocks every single HTMX request on this
  // chain — no network entry, no visible error unless the operator opens the console — confirmed
  // live against a real browser's own CSP violation report on this exact directive.
  /* package */ static final String DASHBOARD_PAGE_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; "
          + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  // img-src gains blob: here and nowhere else: profile-picture-upload.js previews the file a person
  // has just chosen (URL.createObjectURL) before anything is uploaded. A blob: URL can only be
  // minted by a script of this same origin, so it opens no new remote image source.
  //
  // Matches every page under the dashboard app shell — PlatformDashboardSecurityConfig's own
  // securityMatcher already scopes the whole /platform/** chain to ROLE_PLATFORM_ACCOUNT (this
  // sub-path included), so this pattern only needs to distinguish "dashboard" from "login/register/
  // forgot-password" on that same chain, not re-enforce authentication itself.
  //
  // TD-FUT-051, found by the first browser-level test (e2e/): the operator's own Manage account and
  // Sessions pages (/platform/account, /platform/account/sessions) render inside this same app
  // shell and load the same scripts, but were matched by nothing here and so fell through to
  // STRICT_POLICY (script-src 'none'). Every script was refused and the page's dialogs and uploads
  // silently did nothing. They belong to the shell, so they get its policy. The pattern is
  // anchored on "account" followed by "/" or the end, so /platform/account-alert/lock (a one-click
  // link from an email, no shell, no script) stays strict.
  /* package */ static final Pattern DASHBOARD_PAGE_PATH =
      Pattern.compile("^/platform/(dashboard|account)(/.*)?$");

  // TD-FUT-034: the self-service "your passkeys" page's own webauthn-register.js needs both
  // script-src 'self' (to load at all — every other page outside the three named patterns here
  // falls through to STRICT_POLICY's own script-src 'none', which would block the script entirely)
  // and connect-src 'self' (its registration/start and registration/finish fetch() calls) — same
  // two-directive fix LOGIN_PAGE_POLICY's own identical comment documents, scoped to just this one
  // new page rather than widening to all of /o/*/account/** (no sibling self-service page uses a
  // script today).
  /* package */ static final String ACCOUNT_PASSKEYS_PAGE_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
          + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  /* package */ static final Pattern ACCOUNT_PASSKEYS_PAGE_PATH =
      Pattern.compile("^/o/[^/]+/account/passkeys$");

  // Clerk <UserProfile/> parity, pattern (a) — embedded profile, same ADR-0009 §1/§4 mechanism as
  // LOGIN_PAGE_POLICY right above, deliberately not CORS/a new JS SDK (that would reopen ADR-0013's
  // own locked "no cross-origin browser caller" decision — confirmed with the product owner,
  // 2026-10-07): a consuming application iframes this same self-service page
  // (identity/account/profile.html) with ?display=modal&clientId=... on the src URL, exactly the
  // login page's own query-param convention, reusing EmbeddingEligibilityChecker verbatim (no
  // change needed here for this). Real single sign-on, not merely "the embedded login's own first
  // session survives" (real bug found live, 2026-10-10, closed in
  // AdaptiveSameSiteSessionCookieSerializer, not here): an Account that already has an ordinary,
  // non-embedded, top-level session on a real HTTPS deployment is now recognized by this iframe
  // directly, no second login inside it required — only a genuinely brand-new browser (no live
  // Clavaris session at all yet) sees Clavaris's own login form inside the iframe first.
  //
  // script-src 'self' (this template's own i18n.js/organization-dialog.js, same two scripts
  // every dashboard-adjacent page already loads), connect-src 'none' (neither script makes a
  // fetch/XHR call — confirmed by reading organization-dialog.js directly, no HTMX attributes
  // anywhere in this specific template unlike the dashboard's own pages).
  /* package */ static final String ACCOUNT_PROFILE_PAGE_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
          + "font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  /* package */ static final Pattern ACCOUNT_PROFILE_PAGE_PATH =
      Pattern.compile("^/o/[^/]+/account/profile$");

  // See this class's own Javadoc (2026-10-07 security finding) for why this exists. Reused by
  // both login/profile — LOGIN_PAGE_PATH/ACCOUNT_PROFILE_PAGE_PATH already proved the shape, this
  // just captures the same segment.
  @SuppressWarnings("PMD.LongVariable")
  /* package */ static final Pattern ORGANIZATION_SCOPED_PATH_PREFIX =
      Pattern.compile("^/o/([^/]+)/.*$");

  private ContentSecurityPolicies() {}
}
