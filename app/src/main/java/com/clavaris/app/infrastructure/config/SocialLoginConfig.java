package com.clavaris.app.infrastructure.config;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * ADR-0020: the OAuth2 <em>client</em> half of social login — Clavaris authenticating an end-user
 * against Google/GitHub, the opposite role from {@link
 * OrganizationAuthorizationServerConfig}/{@link PlatformAuthorizationServerConfig} (Clavaris as the
 * OAuth2/OIDC <em>server</em>). Deliberately its own chain, not folded into either of those two —
 * mixing {@code oauth2Login()} onto an already heavily-customized {@code
 * OAuth2AuthorizationServerConfigurer} chain would risk exactly the kind of filter-ordering
 * surprise {@code OrganizationAuthorizationServerConfig}'s own Javadoc already documents finding
 * once (three {@code addFilterAfter} calls silently keeping only the last-registered filter) — a
 * completely unrelated concern deserves a completely separate, independently reasoned-about chain.
 *
 * <p>{@code securityMatcher} covers Spring Security's own standard OAuth2 client paths ({@code
 * /oauth2/authorization/**} — the redirect-initiation endpoint {@code oauth2Login()} registers by
 * default, keyed by registration id; {@code /login/oauth2/code/**} — the default callback path)
 * plus {@code SocialLoginRedirectController}'s own two entry points. {@code
 * /platform/login/social/**} would otherwise fall inside {@link PlatformDashboardSecurityConfig}'s
 * broad {@code /platform/**} matcher — this chain is ordered before it (see the two classes' own
 * {@code @Order} values) so the narrower, more specific match here wins.
 *
 * <p>Shares {@link OrganizationAuthorizationServerConfig}'s own {@code securityContextRepository()}
 * bean explicitly, same "share the instance, don't rely on a default" discipline spike 0001's own
 * Appendix B/§5.3 established — not strictly load-bearing here (both {@code
 * SocialLoginAuthenticationSuccessHandler} and every other chain ultimately read/write the same
 * {@code HttpSession} attribute regardless of which repository instance wrote it), but consistent
 * with how every other chain in this codebase wires it, rather than a silent exception to that
 * rule.
 */
@Configuration
class SocialLoginConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ SocialLoginConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  // CLAUDE.md §6 (code review finding): this chain was originally wired with no
  // AntiAbuseRateLimitingFilter at all — every sibling chain that handles a login-shaped
  // unauthenticated endpoint (OrganizationAuthorizationServerConfig,
  // PlatformAuthorizationServerConfig,
  // PlatformDashboardSecurityConfig) already has one; this one, covering four unauthenticated,
  // browser-facing OAuth2-client entry points, did not. IP-only, not per-account: none of these
  // four GET endpoints carry a submitted email/account identifier to key by (unlike the password
  // login form) — same "IP-only" reasoning platform-register/platform-forgot-password already
  // establish for endpoints with no pre-existing identity to key against. One shared limit/window
  // across all four paths, not four independently tuned rows: they're all one login flow's own
  // steps (initiate → provider redirect → callback → landing page), not four functionally
  // distinct endpoints with different abuse shapes.
  @SuppressWarnings("PMD.LongVariable")
  @Bean
  @Order(4)
  /* package */ SecurityFilterChain socialLoginSecurityFilterChain(
      final HttpSecurity http,
      final SecurityContextRepository contextRepository,
      final GitHubVerifiedEmailUserService gitHubUserService,
      final SocialLoginAuthenticationSuccessHandler successHandler,
      final SocialLoginAuthenticationFailureHandler failureHandler,
      final RateLimiter rateLimiter,
      final RateLimitKeyHasher rateLimitKeyHasher,
      @Value("${clavaris.rate-limit.social-login.per-ip-limit:30}") final int perIpLimit) {
    http.securityMatcher(
            "/oauth2/authorization/**",
            "/login/oauth2/code/**",
            "/o/*/login/social/**",
            "/platform/login/social/**")
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .oauth2Login(
            oauth2 ->
                oauth2
                    // Only the non-OIDC (GitHub) delegate needs overriding — Google is OIDC and
                    // Spring's own default OidcUserService already exposes email/email_verified
                    // correctly, no customization needed there.
                    .userInfoEndpoint(userInfo -> userInfo.userService(gitHubUserService))
                    .successHandler(successHandler)
                    .failureHandler(failureHandler))
        .securityContext(context -> context.securityContextRepository(contextRepository))
        .addFilterAfter(
            new AntiAbuseRateLimitingFilter(
                rateLimiter,
                rateLimitKeyHasher,
                List.of(
                    socialLoginIpRule(
                        "social-login-authorization:ip", "/oauth2/authorization/**", perIpLimit),
                    socialLoginIpRule(
                        "social-login-callback:ip", "/login/oauth2/code/**", perIpLimit),
                    socialLoginIpRule(
                        "social-login-landing:ip", "/o/*/login/social/**", perIpLimit),
                    socialLoginIpRule(
                        "platform-social-login-landing:ip",
                        "/platform/login/social/**",
                        perIpLimit))),
            SecurityContextHolderFilter.class)
        .headers(headers -> headers.addHeaderWriter(new ContentSecurityPolicyHeaderWriter()));
    return http.build();
  }

  private static RateLimitRule socialLoginIpRule(
      final String name, final String pathPattern, final int perIpLimit) {
    return new RateLimitRule(
        name,
        HttpMethod.GET,
        pathPattern,
        RateLimitRule.always(),
        RateLimitIdentifiers::sourceIp,
        perIpLimit,
        Duration.ofMinutes(5));
  }
}
