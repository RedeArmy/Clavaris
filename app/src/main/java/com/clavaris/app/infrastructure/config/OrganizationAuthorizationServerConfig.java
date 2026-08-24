package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenUseCase;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.RotateRefreshTokenUseCase;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
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
// This class's whole job is wiring together SAS's own protocol types (ADR-0003: build on the
// framework, never reimplement it) — many distinct collaborators, long descriptive parameter
// names matching SAS's own naming, and the same "PMD.LongVariable" suppression literal repeated
// per-parameter all follow directly from that, not from an organically grown class that should be
// split. TD-SEC-003/BR-ID-03 added the JDBC-backed OAuth2AuthorizationService plus refresh-token
// use case imports/parameters, tipping this over PMD's default thresholds. TD-SEC-019 added one
// more collaborator (BearerTokenHasher), tipping CouplingBetweenObjects' own count from 20 to 21 —
// same "wiring, not sprawl" reasoning, not worth splitting a class whose entire job is assembling
// one SecurityFilterChain bean out of this many legitimately-distinct SAS/Spring Security types.
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.LongVariable",
  "PMD.ExcessiveParameterList",
  "PMD.CouplingBetweenObjects"
})
@Configuration
class OrganizationAuthorizationServerConfig {

  // Appears 4 times across securityMatcher/authorizeHttpRequests/two RateLimitRules below — one
  // constant, not four literals that could silently drift apart (PMD.AvoidDuplicateLiterals).
  private static final String LOGIN_PATH_PATTERN = "/o/*/login";

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

  // TD-SEC-019: declared once, shared by this class's own authorizationService() wiring below and
  // by PlatformAuthorizationServerConfig's identical need — same "share the instance explicitly"
  // precedent as securityContextRepository() above. No default: an unset OAUTH2_TOKEN_HASH_SECRET
  // must fail loudly at startup (TD-SEC-013's own "no silent default" discipline), same posture as
  // TOKEN_SIGNING_KEY_STORE_PASSWORD/PLATFORM_BOOTSTRAP_CLIENT_SECRET — this secret is what stands
  // between a compromised Postgres backup and every currently-valid access/ID token/authorization
  // code being directly usable.
  @Bean
  /* package */ BearerTokenHasher bearerTokenHasher(
      @Value("${clavaris.oauth2.token-hash-secret}") final String tokenHashSecret) {
    return new BearerTokenHasher(tokenHashSecret);
  }

  @Bean
  @Order(3)
  /* package */ SecurityFilterChain organizationAuthorizationServerSecurityFilterChain(
      final HttpSecurity http,
      final OAuthClientRepository oauthClients,
      final SigningKeyRepository signingKeys,
      final OrganizationSigningKeyMaterialFactory keyMaterial,
      final SecurityContextRepository contextRepository,
      final JdbcTemplate jdbcTemplate,
      final BearerTokenHasher bearerTokenHasher,
      final OAuth2TokenCustomizer<JwtEncodingContext> tokenIssuanceLogger,
      final TokenRevocationEventLogger tokenRevocationLogger,
      final IssueRefreshTokenUseCase issueRefreshToken,
      final RotateRefreshTokenUseCase rotateRefreshToken,
      final RateLimiter rateLimiter,
      final RateLimitPolicyRepository rateLimitPolicies,
      @Value("${clavaris.rate-limit.login.per-account-limit:10}") final int loginPerAccountLimit,
      @Value("${clavaris.rate-limit.login.per-ip-limit:30}") final int loginPerIpLimit,
      @Value("${clavaris.rate-limit.token.per-client-limit:20}") final int tokenPerClientLimit,
      @Value("${clavaris.rate-limit.token.refresh-per-client-limit:600}")
          final int tokenRefreshPerClientLimit,
      @Value("${clavaris.rate-limit.capacity.default-requests-per-minute:600}")
          final int capacityDefaultRequestsPerMinute,
      // TD-SEC-008/ADR-0010 §5.2: how long a retired key keeps being published in JWKS after
      // rotation — generous relative to SAS's own default access-token TTL (5 minutes, per
      // incident-response-signing-key-compromise.md's own decompiled-jar finding) to cover
      // longer-lived ID tokens and real-world clock skew between this process and a verifier,
      // without holding a compromised key's material "live" in JWKS indefinitely.
      @Value("${clavaris.signing-key.jwks-overlap-hours:24}") final long jwksOverlapHours) {
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
    //
    // TD-SEC-008: two DELIBERATELY DIFFERENT JWKSource instances, not one shared between them —
    // see OrganizationJwksPublishingSource's own Javadoc for why NimbusJwtEncoder and the JWKS
    // endpoint filter cannot safely share one. signingJwkSource (always exactly the current
    // active key) feeds the encoder that signs new tokens; jwksPublishingSource (active + every
    // still-in-overlap-window retired key) is what setSharedObject registers, which is what the
    // JWKS endpoint filter itself actually resolves and serializes.
    final JWKSource<SecurityContext> signingJwkSource =
        new OrganizationScopedJwkSource(signingKeys, keyMaterial);
    final JWKSource<SecurityContext> jwksPublishingSource =
        new OrganizationJwksPublishingSource(
            signingKeys, keyMaterial, Duration.ofHours(jwksOverlapHours));
    http.setSharedObject(JWKSource.class, jwksPublishingSource);

    final JwtEncoder jwtEncoder = new NimbusJwtEncoder(signingJwkSource);
    final JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
    // TD-SEC-016: every token any Organization issues (client_credentials and, far more commonly,
    // the interactive Authorization Code flow's access + ID tokens) gets a structured
    // event=token_issued log line — see TokenIssuanceEventLogger's own Javadoc.
    jwtGenerator.setJwtCustomizer(tokenIssuanceLogger);
    // BR-ID-03: the initial-issuance half of refresh-token support — JwtGenerator returns null
    // for the non-JWT REFRESH_TOKEN token type, so DelegatingOAuth2TokenGenerator falls through
    // to SessionBackedRefreshTokenGenerator for exactly that case, and only that case (see its
    // own Javadoc for why AUTHORIZATION_CODE-only scoping matters here).
    final OAuth2TokenGenerator<?> tokenGenerator =
        new DelegatingOAuth2TokenGenerator(
            jwtGenerator, new SessionBackedRefreshTokenGenerator(issueRefreshToken));

    final RegisteredClientRepository registeredClients =
        new OrganizationRegisteredClientRepository(oauthClients);

    // TD-SEC-003: same fix, same rationale as the platform tier's own config — see this class's
    // sibling comment there and the migration's own comment for why both tiers deliberately share
    // one physical oauth2_authorization table. This is the tier that actually matters most for
    // BR-ID-03 (refresh tokens): every Organization's interactive Authorization Code + PKCE
    // exchange, not just the low-volume platform client_credentials tier, now survives a restart.
    // TD-SEC-019: wrapped, not passed to .authorizationService(...) directly — every bearer token
    // value this tier ever writes here is HMAC-hashed before it reaches Postgres. See
    // HashedTokenOAuth2AuthorizationService's own Javadoc for the full design.
    @SuppressWarnings("PMD.LongVariable")
    final OAuth2AuthorizationService authorizationService =
        new HashedTokenOAuth2AuthorizationService(
            new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClients), bearerTokenHasher);

    http.securityMatcher(
            "/o/*/oauth2/**", "/o/*/.well-known/**", "/o/*/userinfo", LOGIN_PATH_PATTERN)
        .with(
            new OAuth2AuthorizationServerConfigurer(),
            server ->
                server
                    .registeredClientRepository(registeredClients)
                    .authorizationServerSettings(settings)
                    .authorizationService(authorizationService)
                    .tokenGenerator(tokenGenerator)
                    // BR-ID-03: replaces SAS's own OAuth2RefreshTokenAuthenticationProvider
                    // entirely for the refresh grant — same provider-swap extension point already
                    // used below for ClientSecretAuthenticationProvider's password encoder. See
                    // RefreshTokenRotationAuthenticationProvider's own Javadoc for why this is a
                    // full replacement, not a delegating wrapper.
                    .tokenEndpoint(
                        tokenEndpoint ->
                            tokenEndpoint.authenticationProviders(
                                providers -> {
                                  providers.removeIf(
                                      OAuth2RefreshTokenAuthenticationProvider.class::isInstance);
                                  providers.add(
                                      new RefreshTokenRotationAuthenticationProvider(
                                          authorizationService,
                                          tokenGenerator,
                                          rotateRefreshToken));
                                }))
                    // TD-SEC-017: every successful /oauth2/revoke call on any Organization gets a
                    // structured event=token_revoked log line — see TokenRevocationEventLogger's
                    // own Javadoc for why this replaces (not adds to) SAS's own default handler.
                    .tokenRevocationEndpoint(
                        revocation -> revocation.revocationResponseHandler(tokenRevocationLogger))
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
        //
        // .anyRequest().authenticated() below is NOT what protects /oauth2/authorize from a
        // cross-tier session (see TenantAccountOnlySecurityContextFilter's own Javadoc for why:
        // Spring Authorization Server's own filters read SecurityContextHolder directly and fully
        // handle/commit that request before AuthorizationFilter, the filter this DSL call installs,
        // ever runs). It is kept anyway as a correct, if redundant, statement of intent for this
        // chain and a real backstop for any future path added under this securityMatcher that
        // Spring Authorization Server's own endpoint filters don't fully own.
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(LOGIN_PATH_PATTERN)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .securityContext(context -> context.securityContextRepository(contextRepository))
        // Security finding (SDE-III review, 2026-08-22): the actual fix for cross-tier session
        // confusion on this chain — see TenantAccountOnlySecurityContextFilter's own Javadoc.
        // addFilterAfter(SecurityContextHolderFilter.class), not addFilterBefore any SAS-specific
        // filter, so this runs as early as this chain allows regardless of exactly which SAS
        // filters end up installed for a given request path.
        .addFilterAfter(
            new TenantAccountOnlySecurityContextFilter(), SecurityContextHolderFilter.class)
        // ADR-0010 §6/BR-ID-06/BR-ORG-05: both rate-limiting layers, as early in the chain as
        // possible — before Argon2id password verification (deliberately slow, BR-ID-02) or any
        // SAS-managed endpoint filter runs, so a rejected attempt costs almost nothing to reject.
        .addFilterAfter(
            new AntiAbuseRateLimitingFilter(
                rateLimiter,
                List.of(
                    new RateLimitRule(
                        "login:account",
                        HttpMethod.POST,
                        LOGIN_PATH_PATTERN,
                        RateLimitRule.always(),
                        RateLimitIdentifiers::emailFormField,
                        loginPerAccountLimit,
                        Duration.ofMinutes(5)),
                    new RateLimitRule(
                        "login:ip",
                        HttpMethod.POST,
                        LOGIN_PATH_PATTERN,
                        RateLimitRule.always(),
                        RateLimitIdentifiers::sourceIp,
                        loginPerIpLimit,
                        Duration.ofMinutes(5)),
                    // Non-refresh grants (authorization_code, and any future grant this endpoint
                    // ever accepts) — the credential-adjacent, actually-guessable half of this
                    // endpoint's traffic.
                    new RateLimitRule(
                        "token:client",
                        HttpMethod.POST,
                        "/o/*/oauth2/token",
                        request -> !"refresh_token".equals(request.getParameter("grant_type")),
                        RateLimitIdentifiers::oauthClientId,
                        tokenPerClientLimit,
                        Duration.ofMinutes(5)),
                    // BR-ID-06: "must never throttle a legitimate token-refresh cycle for an
                    // already-active session" — a separate, deliberately much higher ceiling, not
                    // an exemption outright (a genuinely runaway/looping client still deserves a
                    // backstop). Per-client, not per-account: this endpoint has no email field to
                    // key by, and decoding the presented refresh token just to identify its owner
                    // would mean a DB lookup inside a rate-limit filter, which the reuse-detection
                    // check already occurring downstream (BR-ID-03) makes redundant here.
                    new RateLimitRule(
                        "token:refresh",
                        HttpMethod.POST,
                        "/o/*/oauth2/token",
                        request -> "refresh_token".equals(request.getParameter("grant_type")),
                        RateLimitIdentifiers::oauthClientId,
                        tokenRefreshPerClientLimit,
                        Duration.ofMinutes(5)))),
            // Anchored after TenantAccountOnlySecurityContextFilter, not SecurityContextHolder
            // Filter directly — real bug, confirmed live: three separate addFilterAfter calls all
            // anchored at the same filter class silently only kept the last-registered one, so the
            // anti-abuse filter never actually ran. Chaining each new filter off the previous
            // custom one gives every filter its own distinct position, deterministically.
            TenantAccountOnlySecurityContextFilter.class)
        .addFilterAfter(
            new OrganizationCapacityRateLimitingFilter(
                rateLimiter, rateLimitPolicies, capacityDefaultRequestsPerMinute),
            AntiAbuseRateLimitingFilter.class)
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(new OrganizationLoginRedirectEntryPoint()));

    return http.build();
  }
}
