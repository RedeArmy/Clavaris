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
class DefaultSecurityConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ DefaultSecurityConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  // ADR-0024 §3: the one brute-force-relevant endpoint this catch-all chain now serves — a
  // 6-digit email sign-in code is only safe against guessing when paired with attempt throttling
  // (EmailOneTimeCode's own Javadoc names this explicitly). register/forgot-password/reset-password
  // on this same chain predate this need and stay unthrottled here, a pre-existing gap this change
  // doesn't widen, not one this change is responsible for closing.
  @Bean
  @Order(6)
  /* package */ SecurityFilterChain defaultSecurityFilterChain(
      final HttpSecurity http,
      final RateLimiter rateLimiter,
      @SuppressWarnings("PMD.LongVariable") final RateLimitKeyHasher rateLimitKeyHasher,
      @SuppressWarnings("PMD.LongVariable")
          @Value("${clavaris.rate-limit.login-email-code-confirm.per-email-limit:10}")
          final int emailCodeConfirmPerEmailLimit,
      final EmbeddingEligibilityChecker embeddingChecker) {
    http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        // TD-SEC-009: this chain is where RegisterAccountController's own hosted Thymeleaf form
        // (/o/{organizationId}/register) and every other non-SAS/non-dashboard hosted page lives —
        // see ContentSecurityPolicyHeaderWriter's own Javadoc for why this is safe to add
        // unconditionally even though this same chain also serves Actuator/non-HTML responses.
        .headers(
            headers ->
                headers.addHeaderWriter(new ContentSecurityPolicyHeaderWriter(embeddingChecker)))
        .addFilterBefore(
            new AntiAbuseRateLimitingFilter(
                rateLimiter,
                rateLimitKeyHasher,
                List.of(
                    new RateLimitRule(
                        "login-email-code-confirm:email",
                        HttpMethod.POST,
                        "/o/*/login/email-code/confirm",
                        RateLimitRule.always(),
                        RateLimitIdentifiers::emailFormField,
                        emailCodeConfirmPerEmailLimit,
                        Duration.ofMinutes(5)))),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
