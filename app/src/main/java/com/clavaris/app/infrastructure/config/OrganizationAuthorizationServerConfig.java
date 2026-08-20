package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
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
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ADR-0010 §5: one dynamic {@code SecurityFilterChain} serving every {@code Organization}'s own
 * OIDC issuer at {@code {clavarisBaseUrl}/o/{organizationId}} — discovery, JWKS, and the {@code
 * client_credentials} token endpoint (Task #19's scope; the interactive Authorization Code + login
 * flow is Task #20/#21's, not wired here yet, matching CLAUDE.md §11/§12's "don't build ahead of
 * the use case that needs it").
 *
 * <p>Deliberately a single chain for every Organization, not one bean per tenant — the original
 * spike (0001) only validated two hardcoded static chains; its own §6 flagged dynamic,
 * database-driven resolution as unevaluated. That gap is closed in spike 0001's Appendix C addendum
 * (2026-08-19): decompiling the actual resolved SAS 7.1.0 jar (a major version jump from the
 * spike's 1.4.1) showed {@code AuthorizationServerSettings.multipleIssuersAllowed(true)} is now
 * genuine, framework-native, wildcard-prefix multi-tenancy for one deployable — not the
 * reverse-proxy-only pattern the original spike characterised it as. This is the shape that
 * addendum recommends.
 *
 * <p>{@code securityMatcher} is deliberately scoped to the SAS-managed subpaths only ({@code
 * /o/*&#47;oauth2/**}, {@code /o/*&#47;.well-known/**}, {@code /o/*&#47;userinfo}), not the broad
 * {@code /o/**} — {@code RegisterAccountController}'s hosted Thymeleaf form already owns {@code
 * /o/{organizationId}/register} under {@link DefaultSecurityConfig}'s catch-all chain; a broad
 * matcher here would shadow that chain entirely for every org-scoped path (this chain's {@code
 * anyRequest().authenticated()} would apply to the register form too) and break it — caught during
 * design, before it ever reached a live test.
 */
@Configuration
class OrganizationAuthorizationServerConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ OrganizationAuthorizationServerConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  @Bean
  @Order(3)
  /* package */ SecurityFilterChain organizationAuthorizationServerSecurityFilterChain(
      final HttpSecurity http,
      final OAuthClientRepository oauthClients,
      final SigningKeyRepository signingKeys,
      final OrganizationSigningKeyMaterialFactory keyMaterial) {
    // multipleIssuersAllowed requires issuer() to stay unset — SAS's own
    // AuthorizationServerContext-
    // Filter then resolves the issuer per-request from whatever prefix precedes these relative
    // endpoint paths in the actual request URI (spike Appendix C addendum, decompiled and confirmed
    // live: {baseUrl} + {path minus matched endpoint suffix}), e.g. a request to
    // /o/{organizationId}/oauth2/token resolves to issuer {baseUrl}/o/{organizationId}.
    final AuthorizationServerSettings settings =
        AuthorizationServerSettings.builder()
            .multipleIssuersAllowed(true)
            .tokenEndpoint("/oauth2/token")
            .jwkSetEndpoint("/oauth2/jwks")
            .build();

    // The dynamic counterpart to the platform tier's Appendix-B JWKSource: one instance, shared
    // across every Organization, that resolves the current tenant on every call instead of being
    // fixed to a single key pair at construction time.
    final JWKSource<SecurityContext> jwkSource =
        new OrganizationScopedJwkSource(signingKeys, keyMaterial);
    http.setSharedObject(JWKSource.class, jwkSource);

    final JwtEncoder jwtEncoder = new NimbusJwtEncoder(jwkSource);
    final OAuth2TokenGenerator<?> tokenGenerator = new JwtGenerator(jwtEncoder);

    final RegisteredClientRepository registeredClients =
        new OrganizationRegisteredClientRepository(oauthClients);

    http.securityMatcher("/o/*/oauth2/**", "/o/*/.well-known/**", "/o/*/userinfo")
        .with(
            new OAuth2AuthorizationServerConfigurer(),
            server ->
                server
                    .registeredClientRepository(registeredClients)
                    .authorizationServerSettings(settings)
                    .tokenGenerator(tokenGenerator)
                    // A genuine OIDC discovery document (/.well-known/openid-configuration), not
                    // just plain OAuth2 authorization server metadata — ADR-0010 §5 requires every
                    // Organization to have its own *OIDC* issuer, and this is what makes the
                    // discovery filter advertise itself as one.
                    .oidc(Customizer.withDefaults())
                    // Same fix as the platform tier's chain, same root cause: SAS's default
                    // ClientSecretAuthenticationProvider uses Spring Security's
                    // DelegatingPasswordEncoder, which expects an "{id}" bracket prefix; Argon2-
                    // hashed secrets (Argon2ClientSecretHasher, ADR-0005) don't carry one.
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
        // STATELESS: this chain issues client_credentials tokens today; there is no cookie session
        // to protect with a CSRF token — same rationale as the platform tier's own chain.
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable);

    return http.build();
  }
}
