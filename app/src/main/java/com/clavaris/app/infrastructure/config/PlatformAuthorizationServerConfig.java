package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.infrastructure.adapter.out.security.PlatformSigningKeyMaterial;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

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
// This class's whole job is wiring together SAS's own protocol types (ADR-0003: build on the
// framework, never reimplement it) — a high import count here reflects how many distinct SAS/
// Spring Security types one SecurityFilterChain bean legitimately touches, not an organically
// grown class that should be split. TD-SEC-003 added the JDBC-backed OAuth2AuthorizationService
// import trio, tipping this over PMD's default threshold. PMD.AvoidDuplicateLiterals: the
// "PMD.LongVariable" suppression string itself now repeats a 4th time (rate limiting's own new
// param) — same descriptive-parameter-names reasoning as OrganizationAuthorizationServerConfig's
// own identical comment, not worth a named constant purely to satisfy a check about a check.
// PMD.ExcessiveParameterList: TD-SEC-019 added bearerTokenHasher, tipping
// platformAuthorizationServerSecurityFilterChain's own parameter count to the threshold — same
// "wiring, not sprawl" reasoning as OrganizationAuthorizationServerConfig's own identical
// suppression.
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.AvoidDuplicateLiterals",
  "PMD.ExcessiveParameterList"
})
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
      final JdbcTemplate jdbcTemplate,
      final BearerTokenHasher bearerTokenHasher,
      // Descriptive over PMD's default LongVariable threshold, kept in full rather than
      // abbreviated — same convention already used for e.g. passwordCredential elsewhere.
      @SuppressWarnings("PMD.LongVariable")
          final OAuth2TokenCustomizer<JwtEncodingContext> tokenIssuanceLogger,
      @SuppressWarnings("PMD.LongVariable") final TokenRevocationEventLogger tokenRevocationLogger,
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String baseUrl,
      final RateLimiter rateLimiter,
      @SuppressWarnings("PMD.LongVariable")
          @Value("${clavaris.rate-limit.token.per-client-limit:20}")
          final int tokenPerClientLimit) {
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

    // TD-SEC-003: SAS's own well-tested JDBC-backed implementation (ADR-0003 — build on the
    // framework, never reimplement its own state machine), not the default
    // InMemoryOAuth2AuthorizationService — client_credentials exchanges against this tier now
    // survive a restart and are visible to more than one running instance, closing the same root
    // cause TD-SEC-002 already closed for signing keys. See the migration's own comment
    // (V20260821100000__create_oauth2_authorization_table.sql) for why this shares one physical
    // table with the Organization tier's own instance below, rather than the usual two-tables
    // pattern this codebase uses everywhere else platform/tenant state is split.
    // TD-SEC-019: wrapped — see the Organization tier's own identical comment and
    // HashedTokenOAuth2AuthorizationService's Javadoc for the full design.
    @SuppressWarnings("PMD.LongVariable")
    final OAuth2AuthorizationService authorizationService =
        new HashedTokenOAuth2AuthorizationService(
            new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClients), bearerTokenHasher);

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
                    .authorizationService(authorizationService)
                    .tokenGenerator(tokenGenerator)
                    // TD-SEC-017: every successful /oauth2/revoke call on this tier gets a
                    // structured event=token_revoked log line — see TokenRevocationEventLogger's
                    // own Javadoc for why this replaces (not adds to) SAS's own default handler.
                    .tokenRevocationEndpoint(
                        revocation -> revocation.revocationResponseHandler(tokenRevocationLogger))
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
        // ADR-0010 §6.1/BR-ID-06: this tier's own client_credentials-only anti-abuse layer — no
        // refresh_token grant exists here at all (BR-PLATFORM-04: PlatformAccount never issues an
        // OAuth token in the first place), so there's no BR-ID-06 "never throttle refresh"
        // distinction to make, unlike the tenant tier's own token rule. No capacity-layer filter
        // on this chain either — §6.2 is explicitly per-Organization, and the platform tier has
        // no organization_id to aggregate by.
        .addFilterAfter(
            new AntiAbuseRateLimitingFilter(
                rateLimiter,
                List.of(
                    new RateLimitRule(
                        "platform-token:client",
                        HttpMethod.POST,
                        "/oauth2/token",
                        RateLimitRule.always(),
                        RateLimitIdentifiers::oauthClientId,
                        tokenPerClientLimit,
                        Duration.ofMinutes(5)))),
            SecurityContextHolderFilter.class)
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
