package com.clavaris.app.infrastructure.config;

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
final class OrganizationRateLimitRules {

  private OrganizationRateLimitRules() {
    // Static factory only — see this class's own Javadoc.
  }

  // Data, not logic — one parameter per tunable limit, same "wiring, not sprawl" reasoning this
  // codebase's own config classes already apply to their @Bean methods' own parameter lists.
  // PMD.LongVariable: every flagged parameter here matches the exact @Value property name it
  // carries at the call site — same descriptive-over-abbreviated convention as every other
  // config class in this package.
  @SuppressWarnings({"java:S107", "PMD.LongVariable"})
  /* package */ static List<RateLimitRule> all(
      final String loginPathPattern,
      final int loginPerAccountLimit,
      final int loginPerIpLimit,
      final int tokenPerClientLimit,
      final int tokenRefreshPerClientLimit,
      final int accountSessionsListPerAccountLimit,
      final int accountSessionsRevokePerAccountLimit) {
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
        // Non-refresh grants (authorization_code, and any future grant this endpoint ever
        // accepts) — the credential-adjacent, actually-guessable half of this endpoint's traffic.
        new RateLimitRule(
            "token:client",
            HttpMethod.POST,
            "/o/*/oauth2/token",
            request -> !"refresh_token".equals(request.getParameter("grant_type")),
            RateLimitIdentifiers::oauthClientId,
            tokenPerClientLimit,
            Duration.ofMinutes(5)),
        // BR-ID-06: "must never throttle a legitimate token-refresh cycle for an already-active
        // session" — a separate, deliberately much higher ceiling, not an exemption outright (a
        // genuinely runaway/looping client still deserves a backstop). Per-client, not
        // per-account: this endpoint has no email field to key by, and decoding the presented
        // refresh token just to identify its owner would mean a DB lookup inside a rate-limit
        // filter, which the reuse-detection check already occurring downstream (BR-ID-03) makes
        // redundant here.
        new RateLimitRule(
            "token:refresh",
            HttpMethod.POST,
            "/o/*/oauth2/token",
            request -> "refresh_token".equals(request.getParameter("grant_type")),
            RateLimitIdentifiers::oauthClientId,
            tokenRefreshPerClientLimit,
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
            Duration.ofMinutes(5)));
  }
}
