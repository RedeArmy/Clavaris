package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.infrastructure.adapter.out.security.PlatformSigningKeyMaterial;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ADR-0010 (Organization provisioning), BR-PLATFORM-01: {@code client_credentials}-only issuer at
 * {@code {clavarisBaseUrl}/oauth2/...} — root path, never {@code /o/{organizationId}}-prefixed,
 * never reachable through or confusable with any tenant's own OIDC surface. Deliberately does NOT
 * include the spike's Appendix A custom OIDC discovery filter: this issuer is pure OAuth2 {@code
 * client_credentials} for operator/admin tooling, not an OIDC identity flow — nothing currently
 * needs {@code /.well-known/openid-configuration} to exist for it, and the project's own "don't
 * build ahead of the use case that needs it" principle applies here as much as anywhere else. Uses
 * the spike's Appendix B pattern (JWKSource wiring) as-is: docs/03-architecture/spikes/
 * 0001-spring-authorization-server-multitenancy.md §5.3 found that {@code .tokenGenerator(...)}
 * alone only wires the signer — the JWKS endpoint reads its key from a separate shared-object slot
 * that nothing sets by default, silently serving the wrong key otherwise.
 */
@Configuration
class PlatformAuthorizationServerConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ PlatformAuthorizationServerConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  @Bean
  @Order(1)
  /* package */ SecurityFilterChain platformAuthorizationServerSecurityFilterChain(
      final HttpSecurity http,
      final RegisteredClientRepository registeredClients,
      final PlatformSigningKeyMaterial signingKey,
      // Descriptive over PMD's default LongVariable threshold, kept in full rather than
      // abbreviated — same convention already used for e.g. passwordCredential elsewhere.
      @SuppressWarnings("PMD.LongVariable")
          final OAuth2TokenCustomizer<JwtEncodingContext> tokenIssuanceLogger,
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String baseUrl) {
    final AuthorizationServerSettings settings =
        AuthorizationServerSettings.builder()
            .issuer(baseUrl)
            .tokenEndpoint("/oauth2/token")
            .jwkSetEndpoint("/oauth2/jwks")
            .build();

    // Spike Appendix B: one JWKSource instance, shared between the signer and the endpoint that
    // publishes it — NimbusJwkSetEndpointFilter resolves its JWKSource independently via
    // HttpSecurity's own shared-object registry, not from whatever the token generator was given.
    final JWKSource<SecurityContext> jwkSource = platformJwkSource(signingKey);
    http.setSharedObject(JWKSource.class, jwkSource);

    final JwtEncoder jwtEncoder = new NimbusJwtEncoder(jwkSource);
    final JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
    // TD-SEC-016: every token this tier issues (platform-tier client_credentials only) gets a
    // structured event=token_issued log line — see TokenIssuanceEventLogger's own Javadoc.
    jwtGenerator.setJwtCustomizer(tokenIssuanceLogger);
    final OAuth2TokenGenerator<?> tokenGenerator = jwtGenerator;

    http.securityMatcher("/oauth2/**")
        .with(
            // Spring Security 7.x (Spring Boot 4.1's own dependency, resolved 7.1.0 — a major
            // jump from the spike's 1.4.1) replaced the old static
            // OAuth2AuthorizationServerConfigurer.authorizationServer() factory with a plain
            // public constructor — confirmed by decompiling the actual resolved jar, not assumed
            // from the spike's now-outdated example.
            new OAuth2AuthorizationServerConfigurer(),
            server ->
                server
                    .registeredClientRepository(registeredClients)
                    .authorizationServerSettings(settings)
                    .tokenGenerator(tokenGenerator)
                    // Confirmed live: without this, every client_credentials request failed with
                    // "Given that there is no default password encoder configured, each password
                    // must have a password encoding prefix" — SAS's default
                    // ClientSecretAuthenticationProvider uses Spring Security's
                    // DelegatingPasswordEncoder, which expects a "{id}" bracket prefix on stored
                    // hashes to route to the right algorithm. Argon2PasswordEncoder (ADR-0005,
                    // same one client-registry-module's Argon2ClientSecretHasher already hashes
                    // with) produces bare "$argon2id$..." output with no such prefix — this swaps
                    // the provider's encoder to match what's actually stored, rather than
                    // reformatting every stored hash to fit the delegating wrapper's convention.
                    .clientAuthentication(
                        clientAuth ->
                            clientAuth.authenticationProviders(
                                providers ->
                                    providers.stream()
                                        .filter(
                                            ClientSecretAuthenticationProvider.class::isInstance)
                                        .map(ClientSecretAuthenticationProvider.class::cast)
                                        .forEach(
                                            provider ->
                                                provider.setPasswordEncoder(
                                                    Argon2PasswordEncoder
                                                        .defaultsForSpringSecurity_v5_8())))))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        // STATELESS, not left at Spring Security's IF_REQUIRED default: without this, a
        // successful Basic-Auth client_credentials request could still get its SecurityContext
        // persisted to an HttpSession (Spring Security's default SecurityContextRepository
        // behaviour), which would make "no session, no CSRF token to carry" below an assumption
        // rather than something actually enforced by this chain. A client_credentials token
        // endpoint has no reason to remember anything between requests — every call re-presents
        // its own credentials.
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Client authentication itself (Basic-Auth clientId/secret against the PlatformClient's
        // Argon2 hash) is what protects /oauth2/token; STATELESS above rules out a session
        // cookie entirely — no CSRF token to carry, exactly as any client_credentials token
        // endpoint works. securityMatcher already scopes this whole chain to /oauth2/**, so
        // ignoringRequestMatchers("/oauth2/**") would just restate that as a no-op condition —
        // disabling outright says what's actually true.
        .csrf(AbstractHttpConfigurer::disable);

    return http.build();
  }

  private static JWKSource<SecurityContext> platformJwkSource(
      final PlatformSigningKeyMaterial material) {
    final RSAKey rsaKey =
        new RSAKey.Builder((RSAPublicKey) material.keyPair().getPublic())
            .privateKey((RSAPrivateKey) material.keyPair().getPrivate())
            .keyID(material.kid())
            .build();
    return new ImmutableJWKSet<>(new JWKSet(rsaKey));
  }
}
