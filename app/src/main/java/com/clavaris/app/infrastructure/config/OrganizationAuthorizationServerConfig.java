package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenUseCase;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.RotateRefreshTokenUseCase;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

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
// more collaborator (BearerTokenHasher), tipping CouplingBetweenObjects' own count from 20 to 21,
// and TD-SEC-023 added a second (RateLimitKeyHasher) — same "wiring, not sprawl" reasoning, not
// worth splitting a class whose entire job is assembling one SecurityFilterChain bean out of this
// many legitimately-distinct SAS/Spring Security types.
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

  // TD-SEC-028: SAS's own default oidcLogoutEndpoint is "/connect/logout"
  // (AuthorizationServerSettings
  // itself, never overridden here — no reason to rename an endpoint clients only ever reach via the
  // discovery document's own end_session_endpoint claim, never a hardcoded path). Was previously
  // outside this chain's securityMatcher entirely — a request here fell through to
  // DefaultSecurityConfig's catch-all chain, which has no SAS filters at all, so RP-Initiated
  // Logout
  // 404'd unconditionally despite the discovery document confidently advertising it.
  private static final String LOGOUT_PATH_PATTERN = "/o/*/connect/logout";

  // Self-service sessions/devices page (AccountSessionsController) — appears in both
  // securityMatcher and authorizeHttpRequests below, same "one constant, not two literals that
  // could drift" reasoning as LOGIN_PATH_PATTERN/LOGOUT_PATH_PATTERN above.
  @SuppressWarnings("PMD.LongVariable")
  private static final String ACCOUNT_SELF_SERVICE_PATH_PATTERN = "/o/*/account/**";

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ OrganizationAuthorizationServerConfig() {
    // Intentionally empty — this class holds no state, only the @Bean methods below.
  }

  // TD-NEW-1 (SDE-III complexity review, 2026-09-02): the three helpers below factor out
  // organizationAuthorizationServerSecurityFilterChain's own pre-DSL object construction — each is
  // a genuinely self-contained sub-assembly with a narrow, explicit input/output (JWKS/decoder,
  // token-generation pipeline, authorization service), not a fragment of the surrounding wiring.
  // Deliberately NOT extracted: the @Bean method's own parameter list, and the
  // http.securityMatcher(...) fluent DSL chain itself — this class's own Javadoc above already
  // explains why splitting either of those would be the wrong move (the parameter list IS the
  // point: one bean, ~20 legitimately distinct SAS/Spring Security collaborators; the DSL chain IS
  // the SecurityFilterChain assembly, the one thing this bean unavoidably exists to produce). This
  // extraction only shortens the method body's own construction boilerplate, a different axis from
  // the coupling/parameter-count concern that Javadoc defends.

  /**
   * The dynamic, per-request-resolved JWKS pair (TD-SEC-008) plus the {@code /userinfo} decoder
   * built on top of the publishing one — see the call site's own Javadoc for why signing and
   * publishing deliberately stay two different {@link JWKSource} instances.
   */
  private static JwtDecoderAndJwkSources buildJwksAndUserInfoDecoder(
      final SigningKeyRepository signingKeys,
      final OrganizationSigningKeyMaterialFactory keyMaterial,
      final Duration jwksOverlapDuration) {
    final JWKSource<SecurityContext> signingJwkSource =
        new OrganizationScopedJwkSource(signingKeys, keyMaterial);
    final JWKSource<SecurityContext> jwksPublishingSource =
        new OrganizationJwksPublishingSource(signingKeys, keyMaterial, jwksOverlapDuration);
    final NimbusJwtDecoder userInfoJwtDecoder =
        NimbusJwtDecoder.withJwkSource(jwksPublishingSource).build();
    userInfoJwtDecoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(), new OrganizationJwtIssuerValidator()));
    return new JwtDecoderAndJwkSources(signingJwkSource, jwksPublishingSource, userInfoJwtDecoder);
  }

  /** {@code signingJwkSource}, {@code jwksPublishingSource}, {@code userInfoJwtDecoder} bundled. */
  private record JwtDecoderAndJwkSources(
      JWKSource<SecurityContext> signingJwkSource,
      JWKSource<SecurityContext> jwksPublishingSource,
      NimbusJwtDecoder userInfoJwtDecoder) {}

  /**
   * ADR-0016/Workspace-feature/TD-SEC-016 token-issuance pipeline: one {@link JwtEncoder}, every
   * ID-/access-token claims customizer composed into {@code JwtGenerator}'s single customizer slot
   * (see the call site's own Javadoc for why they can't be set as separate calls), and BR-ID-03's
   * refresh-token fallback wired via {@link DelegatingOAuth2TokenGenerator}.
   */
  private static OAuth2TokenGenerator<?> buildTokenGenerator(
      final JWKSource<SecurityContext> signingJwkSource,
      final OAuth2TokenCustomizer<JwtEncodingContext> tokenIssuanceLogger,
      final WorkspaceMembershipRepository workspaceMemberships,
      final IssueRefreshTokenUseCase issueRefreshToken) {
    final JwtEncoder jwtEncoder = new NimbusJwtEncoder(signingJwkSource);
    final JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
    final AuthenticationContextClaimsCustomizer authenticationContextClaims =
        new AuthenticationContextClaimsCustomizer();
    final WorkspaceRoleClaimsCustomizer workspaceRoleClaims =
        new WorkspaceRoleClaimsCustomizer(workspaceMemberships);
    jwtGenerator.setJwtCustomizer(
        context -> {
          tokenIssuanceLogger.customize(context);
          authenticationContextClaims.customize(context);
          workspaceRoleClaims.customize(context);
        });
    return new DelegatingOAuth2TokenGenerator(
        jwtGenerator, new SessionBackedRefreshTokenGenerator(issueRefreshToken));
  }

  /**
   * TD-SEC-003/TD-SEC-019: the JDBC-backed, HMAC-hashed {@link OAuth2AuthorizationService} shared
   * by both this tier's {@code /authorize} and {@code /token} — see the call site's own Javadoc for
   * why this tier (unlike the platform one) has BR-ID-03 refresh-token survivability at stake.
   */
  private static OAuth2AuthorizationService buildAuthorizationService(
      final JdbcTemplate jdbcTemplate,
      final RegisteredClientRepository registeredClients,
      final BearerTokenHasher bearerTokenHasher) {
    return new HashedTokenOAuth2AuthorizationService(
        new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClients), bearerTokenHasher);
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

  // TD-SEC-023: declared once, shared by this class's own AntiAbuseRateLimitingFilter below and by
  // PlatformAuthorizationServerConfig/PlatformDashboardSecurityConfig's identical need — same
  // "share the instance explicitly" precedent as bearerTokenHasher() above. A dedicated secret
  // (clavaris.rate-limit.key-hash-secret), never clavaris.oauth2.token-hash-secret reused — see
  // RateLimitKeyHasher's own Javadoc for why the two must not share one key.
  @Bean
  /* package */ RateLimitKeyHasher rateLimitKeyHasher(
      @Value("${clavaris.rate-limit.key-hash-secret}") final String rateLimitKeyHashSecret) {
    return new RateLimitKeyHasher(rateLimitKeyHashSecret);
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
      final RateLimitKeyHasher rateLimitKeyHasher,
      final OAuth2TokenCustomizer<JwtEncodingContext> tokenIssuanceLogger,
      final TokenRevocationEventLogger tokenRevocationLogger,
      final WorkspaceMembershipRepository workspaceMemberships,
      final IssueRefreshTokenUseCase issueRefreshToken,
      final RotateRefreshTokenUseCase rotateRefreshToken,
      final RateLimiter rateLimiter,
      final RateLimitPolicyRepository rateLimitPolicies,
      final SessionRegistry sessionRegistry,
      @Value("${clavaris.rate-limit.login.per-account-limit:10}") final int loginPerAccountLimit,
      @Value("${clavaris.rate-limit.login.per-ip-limit:30}") final int loginPerIpLimit,
      @Value("${clavaris.rate-limit.token.per-client-limit:20}") final int tokenPerClientLimit,
      @Value("${clavaris.rate-limit.token.refresh-per-client-limit:600}")
          final int tokenRefreshPerClientLimit,
      @Value("${clavaris.rate-limit.capacity.default-requests-per-minute:600}")
          final int capacityDefaultRequestsPerMinute,
      // TD-SEC-035: the sessions/devices page previously had no fixed per-account anti-abuse
      // rule of its own, only the generic, per-tenant-tunable capacity ceiling — a real gap
      // relative to every other sensitive endpoint on this chain (login, token). Listing is the
      // more generous limit (a legitimate user refreshing the page), revoking is deliberately
      // tighter (not something a real user does often).
      @Value("${clavaris.rate-limit.account-sessions.list-per-account-limit:60}")
          final int accountSessionsListPerAccountLimit,
      @Value("${clavaris.rate-limit.account-sessions.revoke-per-account-limit:20}")
          final int accountSessionsRevokePerAccountLimit,
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
    //
    // TD-SEC-028: OidcUserInfoEndpointFilter (registered by .oidc(...) below) reads a
    // JwtAuthenticationToken from SecurityContextHolder, populated by resource-server Bearer-token
    // authentication — SAS's own OAuth2AuthorizationServerConfigurer.init() DOES wire
    // .oauth2ResourceServer(jwt(Customizer.withDefaults())) automatically once OidcUserInfoEndpoint
    // is enabled (decompiled confirmation), but that default customizer expects a JwtDecoder bean
    // to already exist — none did anywhere in this app, so /userinfo requests never authenticated
    // and always fell through to a 401, unnoticed because nothing had ever called it with a real
    // token. userInfoJwtDecoder reuses jwksPublishingSource, not signingJwkSource — a Bearer token
    // presented here may have been signed under a since-rotated key still inside its overlap
    // window (TD-SEC-008), the exact case jwksPublishingSource exists to keep verifiable.
    // Explicitly re-set (not left at JwtValidators.createDefault(), the timestamp-only default)
    // with a delegating validator adding OrganizationJwtIssuerValidator — defense in depth on top
    // of the already-structural guarantee that a cross-tenant token's kid never resolves in a
    // different Organization's own JWKS.
    final JwtDecoderAndJwkSources jwksAndDecoder =
        buildJwksAndUserInfoDecoder(signingKeys, keyMaterial, Duration.ofHours(jwksOverlapHours));
    http.setSharedObject(JWKSource.class, jwksAndDecoder.jwksPublishingSource());
    final NimbusJwtDecoder userInfoJwtDecoder = jwksAndDecoder.userInfoJwtDecoder();

    // ADR-0016 (acr/amr) + Workspace feature (workspace_id/workspace_role, ID and access tokens
    // alike) + TD-SEC-016 (event=token_issued) + BR-ID-03 (refresh-token fallback) — see
    // buildTokenGenerator's own Javadoc for the full per-claim rationale.
    final OAuth2TokenGenerator<?> tokenGenerator =
        buildTokenGenerator(
            jwksAndDecoder.signingJwkSource(),
            tokenIssuanceLogger,
            workspaceMemberships,
            issueRefreshToken);

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
    final OAuth2AuthorizationService authorizationService =
        buildAuthorizationService(jdbcTemplate, registeredClients, bearerTokenHasher);

    http.securityMatcher(
            "/o/*/oauth2/**",
            "/o/*/.well-known/**",
            "/o/*/userinfo",
            LOGIN_PATH_PATTERN,
            LOGOUT_PATH_PATTERN,
            // Self-service sessions/devices page (AccountSessionsController) — narrow and
            // specific, same as every other pattern already listed here; does not widen this
            // matcher to the broad /o/** this class's own Javadoc explains would shadow
            // DefaultSecurityConfig's register/forgot-/reset-password chain.
            ACCOUNT_SELF_SERVICE_PATH_PATTERN)
        // TD-SEC-028: registered before .with(new OAuth2AuthorizationServerConfigurer(), ...)
        // below so this decoder is already set by the time that configurer's own init() applies
        // its internal .oauth2ResourceServer(jwt(Customizer.withDefaults())) call (decompiled
        // confirmation: it targets the SAME configurer instance, not a second one) — Spring
        // Security's DSL customizers compose, they don't overwrite, so SAS's own no-op default on
        // top of this real decoder is harmless, live-verified end to end (userinfo returns real
        // claims for a valid token, 401s for none).
        .oauth2ResourceServer(
            resourceServer -> resourceServer.jwt(jwt -> jwt.decoder(userInfoJwtDecoder)))
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
                    // discovery filter advertise itself as one. Custom userInfoMapper (Workspace
                    // feature follow-up): SAS's own default one silently drops workspace_id/
                    // workspace_role from /userinfo — see WorkspaceAwareOidcUserInfoMapper's own
                    // Javadoc for why, confirmed live via javap, not assumed.
                    .oidc(
                        oidc ->
                            oidc.userInfoEndpoint(
                                userInfo ->
                                    userInfo.userInfoMapper(
                                        new WorkspaceAwareOidcUserInfoMapper())))
                    // Same fix as the platform tier's chain, same root cause — extracted to
                    // Argon2ClientAuthenticationSupport (code review finding: this exact block was
                    // duplicated byte-for-byte across both chains) — see that class's own Javadoc.
                    .clientAuthentication(
                        clientAuth ->
                            clientAuth.authenticationProviders(
                                Argon2ClientAuthenticationSupport::useArgon2PasswordEncoder)))
        // /o/*/login is where an unauthenticated /oauth2/authorize request gets redirected to
        // (below) — it must be reachable pre-authentication, or the redirect loops back on itself.
        // /o/*/connect/logout (TD-SEC-028) is permitAll for the same class of reason, not by
        // coincidence: OIDC RP-Initiated Logout is explicitly designed to still work once the
        // browser's own session at this issuer has already expired or been cleared — the request's
        // own id_token_hint parameter is what OidcLogoutAuthenticationProvider actually validates
        // (decompiled confirmation: it accepts both an authenticated and an anonymous principal),
        // not ambient session state, so gating this behind .anyRequest().authenticated() would
        // reject exactly the case this endpoint exists to handle.
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
                    .requestMatchers(LOGIN_PATH_PATTERN, LOGOUT_PATH_PATTERN)
                    .permitAll()
                    // Explicit hasAuthority, not left to fall under the generic
                    // .anyRequest().authenticated() below — technically redundant given
                    // TenantAccountOnlySecurityContextFilter already strips a wrong-tier context
                    // earlier in this exact chain, but this codebase's own security history (the
                    // PlatformDashboardSecurityConfig .authenticated()-vs-hasAuthority incident,
                    // SDE-III review 2026-08-22) is reason enough for real defense-in-depth on a
                    // brand-new page that lists/revokes live sessions.
                    .requestMatchers(ACCOUNT_SELF_SERVICE_PATH_PATTERN)
                    .hasAuthority("ROLE_ACCOUNT")
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
                rateLimitKeyHasher,
                // Code review finding: this rule list is pure configuration data, extracted to
                // OrganizationRateLimitRules — see that class's own Javadoc. Same rules, same
                // order, same parameters as before, just no longer inline here.
                OrganizationRateLimitRules.all(
                    LOGIN_PATH_PATTERN,
                    loginPerAccountLimit,
                    loginPerIpLimit,
                    tokenPerClientLimit,
                    tokenRefreshPerClientLimit,
                    accountSessionsListPerAccountLimit,
                    accountSessionsRevokePerAccountLimit)),
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
        // TD-SEC-031 (SDE-III review, 2026-08-26): without this, AccountSessionRevoker's own
        // expireNow() call marks a SessionRegistry entry nobody on this chain ever checked — same
        // "expireNow() alone doesn't reject the next request" gap PlatformDashboardSecurityConfig's
        // own ConcurrentSessionFilter wiring already closed for the platform tier. Wrapped, not the
        // plain sessionManagement().sessionConcurrency() DSL — see TenantSessionConcurrencyFilter's
        // own Javadoc for the real integration-test-caught regression that DSL's own unconditional
        // reach caused against this chain's machine-authenticated endpoints, and why this exempts
        // them explicitly rather than routing around the symptom.
        .addFilterAfter(
            new TenantSessionConcurrencyFilter(sessionRegistry),
            OrganizationCapacityRateLimitingFilter.class)
        // TD-SEC-028: /userinfo is a Bearer-token-protected API endpoint, not a browser page — a
        // real OIDC client calling it with a missing/invalid access token needs a clean 401,
        // never OrganizationLoginRedirectEntryPoint's redirect-to-the-hosted-login-page (correct
        // for every OTHER unauthenticated request on this chain, since those genuinely are
        // browser navigations). Built directly, not via ExceptionHandlingConfigurer's own
        // defaultAuthenticationEntryPointFor(...) DSL method — decompiled confirmation
        // (ExceptionHandlingConfigurer.getAuthenticationEntryPoint(H)) that method is silently a
        // no-op whenever .authenticationEntryPoint(...) is ALSO called on the same configurer (the
        // plain entry point wins outright, unconditionally, if set at all) — this chain needs
        // both a matcher-specific AND a fallback entry point, so the delegating dispatch has to be
        // built once, here, and handed to .authenticationEntryPoint(...) as the single value.
        // Live-verified: the first-ever test to call /userinfo with no token got back a 302, not
        // a 401, before this was added — a real gap, not a hypothetical one.
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(
                    DelegatingAuthenticationEntryPoint.builder()
                        .defaultEntryPoint(new OrganizationLoginRedirectEntryPoint())
                        .addEntryPointFor(
                            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                            PathPatternRequestMatcher.pathPattern("/o/*/userinfo"))
                        .build()))
        // TD-SEC-009: the one chain serving both this project's own /o/*/login template AND SAS's
        // own default consent page — see ContentSecurityPolicyHeaderWriter's own Javadoc for why
        // each gets a different policy and how it tells them apart.
        .headers(headers -> headers.addHeaderWriter(new ContentSecurityPolicyHeaderWriter()));

    return http.build();
  }
}
