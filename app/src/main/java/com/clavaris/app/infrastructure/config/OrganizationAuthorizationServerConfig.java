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
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
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
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * ADR-0010 §5: one dynamic {@code SecurityFilterChain} serving every {@code Organization}'s own
 * OIDC issuer at {@code {clavarisBaseUrl}/o/{organizationId}} — discovery, JWKS, the {@code
 * client_credentials} token endpoint, and the interactive Authorization Code + PKCE flow (hosted
 * login page at {@code /o/{organizationId}/login}, backed by {@code
 * AuthenticateWithPasswordUseCase} via {@link SpringSecurityAuthenticatedSessionEstablisher}).
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
 * <p>Also a single chain for the {@code client_credentials} token endpoint and the interactive
 * {@code /authorize} + login flow, not two separate ones — splitting them would mean building two
 * independent {@code OAuth2AuthorizationServerConfigurer} instances, each with its own default
 * in-memory {@code OAuth2AuthorizationService}; an authorization code issued by one chain's {@code
 * /authorize} would then be invisible to the other chain's {@code /token}. One chain keeps that
 * state naturally shared.
 *
 * <p>Not {@code SessionCreationPolicy.STATELESS} any more, and CSRF is no longer disabled — both
 * were correct for Task #19's client_credentials-only scope but not for this one: a real
 * cookie-session-backed login form now exists on this chain (same reasoning that drove {@code
 * DefaultSecurityConfig}'s own CSRF decision for {@code RegisterAccountController}'s form).
 * Decompiling {@code OAuth2AuthorizationServerConfigurer.init()} confirmed it unconditionally calls
 * {@code http.csrf(csrf -> csrf.ignoringRequestMatchers(endpointsMatcher))} — every SAS-managed
 * endpoint (including {@code /oauth2/authorize} itself) is already CSRF-exempt by the framework,
 * regardless of this chain's own CSRF setting; leaving CSRF at Spring Security's default (enabled)
 * only ends up protecting {@code /o/{organizationId}/login}, the one path on this chain that isn't
 * one of SAS's own endpoints. {@code client_credentials} calls never carry a browser cookie, so
 * removing {@code STATELESS} doesn't change their behaviour — nothing in that request path ever
 * calls {@code request.getSession()}.
 *
 * <p>{@code securityMatcher} is deliberately scoped to the SAS-managed subpaths plus the login page
 * ({@code /o/*&#47;oauth2/**}, {@code /o/*&#47;.well-known/**}, {@code /o/*&#47;userinfo}, {@code
 * /o/*&#47;login}), not the broad {@code /o/**} — {@code RegisterAccountController}'s hosted
 * Thymeleaf form still owns {@code /o/{organizationId}/register} under {@link
 * DefaultSecurityConfig}'s catch-all chain; a broad matcher here would shadow that chain entirely
 * for every org-scoped path (this chain's {@code anyRequest().authenticated()} would apply to the
 * register form too) and break it.
 */
@Configuration
class OrganizationAuthorizationServerConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ OrganizationAuthorizationServerConfig() {
    // Intentionally empty — this class holds no state, only the @Bean methods below.
  }

  // Explicit, named bean — not left to whatever HttpSecurity's own default resolution would pick —
  // so this chain's SecurityContextHolderFilter and SpringSecurityAuthenticatedSessionEstablisher
  // (which persists the context from plain controller code, not a filter) provably agree on the
  // same repository instance. Same "share the instance explicitly, don't rely on a default" lesson
  // spike 0001's Appendix B/§5.3 already taught this codebase for JWKSource.
  @Bean
  /* package */ SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  @Order(3)
  /* package */ SecurityFilterChain organizationAuthorizationServerSecurityFilterChain(
      final HttpSecurity http,
      final OAuthClientRepository oauthClients,
      final SigningKeyRepository signingKeys,
      final OrganizationSigningKeyMaterialFactory keyMaterial,
      final SecurityContextRepository contextRepository,
      // Descriptive over PMD's default LongVariable threshold, kept in full rather than
      // abbreviated — same convention already used for e.g. passwordCredential elsewhere.
      @SuppressWarnings("PMD.LongVariable")
          final OAuth2TokenCustomizer<JwtEncodingContext> tokenIssuanceLogger) {
    // multipleIssuersAllowed requires issuer() to stay unset — SAS's own AuthorizationServerContext
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
    final JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
    // TD-SEC-016: every token any Organization issues (client_credentials and, far more commonly,
    // the interactive Authorization Code flow's access + ID tokens) gets a structured
    // event=token_issued log line — see TokenIssuanceEventLogger's own Javadoc.
    jwtGenerator.setJwtCustomizer(tokenIssuanceLogger);
    final OAuth2TokenGenerator<?> tokenGenerator = jwtGenerator;

    final RegisteredClientRepository registeredClients =
        new OrganizationRegisteredClientRepository(oauthClients);

    http.securityMatcher("/o/*/oauth2/**", "/o/*/.well-known/**", "/o/*/userinfo", "/o/*/login")
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
        // /o/*/login is where an unauthenticated /oauth2/authorize request gets redirected to
        // (below) — it must be reachable pre-authentication, or the redirect loops back on itself.
        .authorizeHttpRequests(
            authorize ->
                authorize.requestMatchers("/o/*/login").permitAll().anyRequest().authenticated())
        .securityContext(context -> context.securityContextRepository(contextRepository))
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(new OrganizationLoginRedirectEntryPoint()));

    return http.build();
  }
}
