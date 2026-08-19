package com.clavaris.app.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything not matched by {@link PlatformAuthorizationServerConfig} (/oauth2/**) or {@link
 * AdminApiSecurityConfig} (/api/v1/admin/**) — the hosted UI ({@code /o/{organizationId}/register},
 * RegisterAccount's own Thymeleaf form) and Actuator health checks. Adding Spring Security to app's
 * classpath for the first time (needed for the two OAuth2-specific chains above) activates its
 * autoconfiguration for the *whole* application by default — without this catch-all chain, every
 * existing endpoint would suddenly require authentication against a random generated password, a
 * real regression confirmed live while building this. Kept exactly as permissive as the
 * pre-Spring-Security state was: nothing here was protected before, nothing here is protected now.
 *
 * <p>Known, explicitly-flagged gap, not a silent omission: RegisterAccount's own hosted-UI form has
 * no CSRF protection, before or after this change — real hardening, out of scope for "add
 * platform-tier authentication," tracked as follow-up work rather than scope-crept into here.
 */
@Configuration
class DefaultSecurityConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ DefaultSecurityConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  // HttpSecurity's own fluent API declares `throws Exception` on every configurer method (Spring
  // Security's own contract, not something a caller can narrow) — same idiom on every
  // SecurityFilterChain @Bean method in this codebase.
  @SuppressWarnings("PMD.SignatureDeclareThrowsException")
  @Bean
  @Order(3)
  /* package */ SecurityFilterChain defaultSecurityFilterChain(final HttpSecurity http)
      throws Exception {
    http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .csrf(AbstractHttpConfigurer::disable);
    return http.build();
  }
}
