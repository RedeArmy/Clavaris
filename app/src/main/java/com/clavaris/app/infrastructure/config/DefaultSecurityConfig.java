package com.clavaris.app.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything not matched by {@link PlatformAuthorizationServerConfig} (/oauth2/**), {@link
 * AdminApiSecurityConfig} (/api/v1/admin/**), or {@link OrganizationAuthorizationServerConfig}
 * (/o/*&#47;oauth2/**, /o/*&#47;.well-known/**, /o/*&#47;userinfo) — the hosted UI ({@code
 * /o/{organizationId}/register}, RegisterAccount's own Thymeleaf form) and Actuator health checks.
 * Adding Spring Security to app's classpath for the first time (needed for the two OAuth2-specific
 * chains above) activates its autoconfiguration for the *whole* application by default — without
 * this catch-all chain, every existing endpoint would suddenly require authentication against a
 * random generated password, a real regression confirmed live while building this. Kept exactly as
 * permissive as the pre-Spring-Security state was regarding *authentication* — {@code
 * anyRequest().permitAll()}, nothing here required login before, nothing does now.
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

  @Bean
  @Order(4)
  /* package */ SecurityFilterChain defaultSecurityFilterChain(final HttpSecurity http) {
    http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
    return http.build();
  }
}
