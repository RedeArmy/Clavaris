package com.clavaris.app.infrastructure.adapter.in.web.filter;

import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * Code review finding (2026-09-01): the {@link RateLimitRule} list {@code
 * organizationAuthorizationServerSecurityFilterChain} hands its {@link AntiAbuseRateLimitingFilter}
 * is pure configuration data, not chain-wiring logic — it doesn't need to live inline inside that
 * ~400-line method body to be understood or changed. Extracted here so this tier's own anti-abuse
 * rules (ADR-0010 §6.1/BR-ID-06/BR-ORG-05, TD-SEC-035) can be read and reasoned about on their own,
 * without also holding the JWKS/JWT-generator/OAuth2AuthorizationServerConfigurer wiring in view at
 * the same time. Behavior is unchanged — same rules, same order, same parameters, just moved.
 */
public final class OrganizationRateLimitRules {

  private OrganizationRateLimitRules() {
    // Static factory only — see this class's own Javadoc.
  }

  // Data, not logic — one parameter per tunable limit, same "wiring, not sprawl" reasoning this
  // codebase's own config classes already apply to their @Bean methods' own parameter lists.
  // PMD.LongVariable: every flagged parameter here matches the exact @Value property name it
  // carries at the call site — same descriptive-over-abbreviated convention as every other
  // config class in this package.
  @SuppressWarnings({"java:S107", "PMD.LongVariable"})
  public static List<RateLimitRule> all(
      final String loginPathPattern,
      final int loginPerAccountLimit,
      final int loginPerIpLimit,
      final int tokenPerClientIpLimit,
      final int tokenRefreshPerClientIpLimit,
      final int accountSessionsListPerAccountLimit,
      final int accountSessionsRevokePerAccountLimit,
      final int accountPictureUploadPerAccountLimit,
      final int credentialChangePerAccountLimit) {
    return List.of(
        new RateLimitRule(
            "login:account",
            HttpMethod.POST,
            loginPathPattern,
            RateLimitRule.always(),
            RateLimitIdentifiers::emailFormField,
            loginPerAccountLimit,
            Duration.ofMinutes(5)),
        new RateLimitRule(
            "login:ip",
            HttpMethod.POST,
            loginPathPattern,
            RateLimitRule.always(),
            RateLimitIdentifiers::sourceIp,
            loginPerIpLimit,
            Duration.ofMinutes(5)),
        // Non-refresh grants (authorization_code, client_credentials, anything else this endpoint
        // ever accepts). TD-PERF-034: this used to be 20 per 5 minutes per claimed client_id, which
        // did two things wrong at once. It counted every request, including the successful
        // exchange of a single-use, PKCE-bound code, so a whole consuming application could
        // complete about four sign-ins a minute. And the key was a public, unauthenticated string,
        // so anyone who knew a client_id could spend its allowance with 20 garbage requests and
        // lock that application out for the window. Now keyed by client_id and source IP (a caller
        // can only exhaust their own allowance) with a limit that is a runaway backstop, not the
        // capacity control: it sits at the order of the system hard cap, so it can never bind
        // before
        // the Organization's own capacity ceiling does (OrganizationCapacityRateLimitingFilter).
        // It does not need to throttle secret guessing: every tenant client secret is generated
        // with 256 bits, so there is nothing to guess at any rate. The platform tier is different
        // (its bootstrap secret is chosen by an operator) and keeps its own, much stricter rule.
        new RateLimitRule(
            "token:client",
            HttpMethod.POST,
            "/o/*/oauth2/token",
            request -> !"refresh_token".equals(request.getParameter("grant_type")),
            RateLimitIdentifiers::oauthClientIdAndSourceIp,
            tokenPerClientIpLimit,
            Duration.ofMinutes(5)),
        // BR-ID-06: "must never throttle a legitimate token-refresh cycle for an already-active
        // session" — a separate, deliberately lower ceiling than the rule above, not an exemption
        // outright (a genuinely runaway/looping client still deserves a backstop). Keyed like the
        // rule above, by client and source IP, for the same reason. This endpoint has no email
        // field to key by, and decoding the presented refresh token just to identify its owner
        // would mean a DB lookup inside a rate-limit filter, which the reuse-detection check
        // already occurring downstream (BR-ID-03) makes redundant here.
        new RateLimitRule(
            "token:refresh",
            HttpMethod.POST,
            "/o/*/oauth2/token",
            request -> "refresh_token".equals(request.getParameter("grant_type")),
            RateLimitIdentifiers::oauthClientIdAndSourceIp,
            tokenRefreshPerClientIpLimit,
            Duration.ofMinutes(5)),
        // TD-SEC-035: keyed by the already-authenticated AccountId, not IP — every request this
        // rule ever sees has already passed hasAuthority(ROLE_ACCOUNT) (this filter runs after
        // TenantAccountOnlySecurityContextFilter), so there is always a real principal to key by,
        // same convention PlatformDashboardSecurityConfig's own create-organization rule already
        // establishes for its own tier.
        new RateLimitRule(
            "account-sessions:list",
            HttpMethod.GET,
            "/o/*/account/sessions",
            RateLimitRule.always(),
            RateLimitIdentifiers::authenticatedAccountId,
            accountSessionsListPerAccountLimit,
            Duration.ofMinutes(5)),
        new RateLimitRule(
            "account-sessions:revoke",
            HttpMethod.POST,
            "/o/*/account/sessions/*/revoke",
            RateLimitRule.always(),
            RateLimitIdentifiers::authenticatedAccountId,
            accountSessionsRevokePerAccountLimit,
            Duration.ofMinutes(5)),
        // SDE-III review, 2026-09-21 — SonarCloud multipart-size hotspot follow-up
        // (application.yml's own spring.servlet.multipart.max-file-size: 11MB): that framework
        // ceiling alone let an authenticated Account fire unlimited ~11MB uploads with no
        // backoff, a real resource-exhaustion vector the ceiling itself doesn't close — same
        // TD-SEC-035 gap class as account-sessions above, closed the same way.
        new RateLimitRule(
            "account-picture:upload",
            HttpMethod.POST,
            "/o/*/account/profile/picture",
            RateLimitRule.always(),
            RateLimitIdentifiers::authenticatedAccountId,
            accountPictureUploadPerAccountLimit,
            Duration.ofMinutes(5)),
        // TD-FUT-043: both forms below ask for the current password, so each is a password oracle
        // for anyone holding a signed-in session (a stolen cookie, an unattended browser). Keyed by
        // the authenticated Account like the rules above, and well under the sign-in limit.
        new RateLimitRule(
            "account-password:change",
            HttpMethod.POST,
            "/o/*/account/password",
            RateLimitRule.always(),
            RateLimitIdentifiers::authenticatedAccountId,
            credentialChangePerAccountLimit,
            Duration.ofMinutes(5)),
        new RateLimitRule(
            "account-email:change",
            HttpMethod.POST,
            "/o/*/account/email",
            RateLimitRule.always(),
            RateLimitIdentifiers::authenticatedAccountId,
            credentialChangePerAccountLimit,
            Duration.ofMinutes(5)));
  }
}
