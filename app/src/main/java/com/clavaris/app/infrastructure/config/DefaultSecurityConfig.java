package com.clavaris.app.infrastructure.config;

import com.clavaris.app.infrastructure.adapter.in.web.filter.AntiAbuseRateLimitingFilter;
import com.clavaris.app.infrastructure.adapter.in.web.filter.HostedPageRateLimitRules;
import com.clavaris.app.infrastructure.adapter.in.web.filter.RateLimiter;
import com.clavaris.app.infrastructure.adapter.out.bridge.EmbeddingEligibilityChecker;
import com.clavaris.app.infrastructure.adapter.out.bridge.RedirectUriOriginResolver;
import com.clavaris.app.infrastructure.adapter.out.security.RateLimitKeyHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Everything not matched by {@link PlatformAuthorizationServerConfig} (/oauth2/**), {@link
 * AdminApiSecurityConfig} (/api/v1/admin/**), {@link OrganizationAuthorizationServerConfig}
 * (/o/*&#47;oauth2/**, /o/*&#47;.well-known/**, /o/*&#47;userinfo), {@link SocialLoginConfig}
 * (/oauth2/authorization/**, /login/oauth2/code/**, /o/*&#47;login/social/**,
 * /platform/login/social/**), or {@link PlatformDashboardSecurityConfig} (/platform/**) — the
 * hosted UI ({@code /o/{organizationId}/register}, RegisterAccount's own Thymeleaf form) and
 * Actuator health checks. Adding Spring Security to app's classpath for the first time (needed for
 * the two OAuth2-specific chains above) activates its autoconfiguration for the *whole* application
 * by default — without this catch-all chain, every existing endpoint would suddenly require
 * authentication against a random generated password, a real regression confirmed live while
 * building this. Kept exactly as permissive as the pre-Spring-Security state was regarding
 * *authentication* — {@code anyRequest().permitAll()}, nothing here required login before, nothing
 * does now.
 *
 * <p>CSRF, unlike authentication, is deliberately left at Spring Security's own default (enabled)
 * rather than disabled — a SonarCloud CSRF hotspot review (java:S4502) on an earlier version of
 * this class correctly flagged that "nothing here needs it" was true for the two Bearer-token
 * chains above but not for this one: {@code RegisterAccountController}'s POST form is exactly the
 * cookie-session-backed flow CSRF protection exists for. {@code register.html} carries the token
 * (Spring's default {@code _csrf} request attribute, no extra Thymeleaf-Security integration
 * library needed), and {@code RegisterAccountCsrfIntegrationTest} proves both directions live: a
 * POST without the token is rejected, one with it succeeds.
 */
@Configuration
public class DefaultSecurityConfig {

  public DefaultSecurityConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  // ADR-0024 §3: the one brute-force-relevant endpoint this catch-all chain now serves — a
  // one-time email sign-in code is only safe against guessing when paired with attempt throttling
  // (EmailOneTimeCode's own Javadoc names this explicitly). The requests that send an email
  // (sign-in code, sign-in link, forgot-password) are limited per address and per IP too, since
  // each sends a real message. register and reset-password on this same chain stay unthrottled
  // here, a pre-existing gap this change doesn't widen.
  // One parameter per tunable limit and per collaborator, as in the other chains; the limits are
  // read from @Value, so the chain does not hold them in a holder object.
  @Bean
  @Order(6)
  public SecurityFilterChain defaultSecurityFilterChain(
      final HttpSecurity http,
      final RateLimiter rateLimiter,
      final RateLimitKeyHasher rateLimitKeyHasher,
      @Value("${clavaris.rate-limit.login-email-code-confirm.per-email-limit:10}")
          final int emailCodeConfirmPerEmailLimit,
      // Asking for a sign-in code, a sign-in link or a password reset sends a real email, so an
      // unthrottled request is both a way to flood a victim's mailbox and a cost on the mail
      // provider. Per address and per source IP, over ten minutes, the lifetime of the code itself.
      @Value("${clavaris.rate-limit.email-request.per-email-limit:5}")
          final int emailRequestPerEmailLimit,
      @Value("${clavaris.rate-limit.email-request.per-ip-limit:20}")
          final int emailRequestPerIpLimit,
      // The two confirmations that cannot be counted per address: the sign-in link carries only an
      // unguessable token and the device code only a session, so neither has an email field to key
      // on. They get a burst limit per source IP here; what bounds the wrong codes of an account
      // over time is the consecutive-failure lock (ADR-0033), not a rate limit.
      @Value("${clavaris.rate-limit.code-confirm.per-ip-limit:30}") final int codeConfirmPerIpLimit,
      // Passkey sign-in: a challenge is opened on page loads, not only on a deliberate attempt, so
      // this
      // is higher than the code limit (TD-SEC-085).
      @Value("${clavaris.rate-limit.webauthn.per-ip-limit:120}") final int webauthnPerIpLimit,
      final EmbeddingEligibilityChecker embeddingChecker,
      final RedirectUriOriginResolver redirectUriOriginResolver) {
    http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        // TD-SEC-009: this chain is where RegisterAccountController's own hosted Thymeleaf form
        // (/o/{organizationId}/register) and every other non-SAS/non-dashboard hosted page lives —
        // see ContentSecurityPolicyHeaderWriter's own Javadoc for why this is safe to add
        // unconditionally even though this same chain also serves Actuator/non-HTML responses.
        .headers(
            headers ->
                headers.addHeaderWriter(
                    new ContentSecurityPolicyHeaderWriter(
                        embeddingChecker, redirectUriOriginResolver)))
        .addFilterBefore(
            new AntiAbuseRateLimitingFilter(
                rateLimiter,
                rateLimitKeyHasher,
                HostedPageRateLimitRules.all(
                    emailCodeConfirmPerEmailLimit,
                    emailRequestPerEmailLimit,
                    emailRequestPerIpLimit,
                    codeConfirmPerIpLimit,
                    webauthnPerIpLimit)),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
