package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.domain.model.PlatformScopes;
import com.clavaris.identity.infrastructure.adapter.out.security.PlatformSigningKeyMaterial;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
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

  @Bean
  @Order(2)
  /* package */ SecurityFilterChain adminApiSecurityFilterChain(
      final HttpSecurity http, final JwtDecoder jwtDecoder) {
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
                    // ADR-0010 §6.2: tuning the capacity-layer ceiling is its own scope too, same
                    // defence-in-depth reasoning as Organization creation above — a platform
                    // token that can create Organizations doesn't automatically get to also
                    // change a running one's rate-limit ceiling.
                    .requestMatchers(
                        HttpMethod.PUT, "/api/v1/admin/organizations/*/rate-limit-policy")
                    .hasAuthority("SCOPE_" + PlatformScopes.RATE_LIMIT_POLICY_WRITE)
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
        // Resource server, STATELESS session policy below, and securityMatcher already scopes
        // this whole chain to /api/v1/admin/** — every request reaching this point authenticates
        // via a Bearer token, never a cookie-based session, so there's no CSRF token to carry in
        // the first place. ignoringRequestMatchers(the same path already gating the chain) would
        // just restate that as a no-op condition; disabling outright says what's actually true.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

    return http.build();
  }
}
