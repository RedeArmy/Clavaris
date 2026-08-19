package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.domain.model.PlatformScopes;
import com.clavaris.identity.infrastructure.adapter.out.security.PlatformSigningKeyMaterial;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * BR-PLATFORM-02: the entire {@code /api/v1/admin/*} surface accepts platform-tier tokens only — no
 * tenant's own {@code OAuthClient} is ever accepted here, not even for actions on its own
 * Organization. Validates the incoming Bearer token's signature directly against {@link
 * PlatformSigningKeyMaterial}'s own public key — no HTTP round-trip to this same process's own
 * {@code /oauth2/jwks} endpoint, since the key is already in memory right here.
 */
@Configuration
class AdminApiSecurityConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ AdminApiSecurityConfig() {
    // Intentionally empty — this class holds no state, only the @Bean methods below.
  }

  @Bean
  /* package */ JwtDecoder platformJwtDecoder(final PlatformSigningKeyMaterial platformKey) {
    return NimbusJwtDecoder.withPublicKey((RSAPublicKey) platformKey.keyPair().getPublic()).build();
  }

  // HttpSecurity's own fluent API declares `throws Exception` on every configurer method
  // (Spring Security's own contract, not something a caller can narrow) — same idiom on every
  // SecurityFilterChain @Bean method in this codebase (PlatformAuthorizationServerConfig,
  // DefaultSecurityConfig).
  @SuppressWarnings("PMD.SignatureDeclareThrowsException")
  @Bean
  @Order(2)
  /* package */ SecurityFilterChain adminApiSecurityFilterChain(
      final HttpSecurity http, final JwtDecoder jwtDecoder) throws Exception {
    http.securityMatcher("/api/v1/admin/**")
        .authorizeHttpRequests(
            authorize ->
                authorize
                    // BR-ORG-06: creating an Organization is gated by its own scope, not just
                    // "any valid platform token" — defence in depth beyond BR-PLATFORM-02's
                    // blanket platform-tier-only rule, matching the scope namespace
                    // PlatformScopes already reserves for this exact action.
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/organizations")
                    .hasAuthority("SCOPE_" + PlatformScopes.ORGANIZATIONS_WRITE)
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
        // A Bearer token, not a browser session — no CSRF token to carry, same as the platform
        // issuer's own token endpoint.
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/admin/**"))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

    return http.build();
  }
}
