package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * SDE-III feature build, 2026-09-03 (Impersonation): the token-minting half of the feature — see
 * {@code com.clavaris.identity.application.usecase.impersonateaccount.ImpersonateAccountCommand}'s
 * own Javadoc for why validation/auditing lives in identity-module while this, the actual JWT
 * issuance, lives here instead. Deliberately mints an access token only, never an ID token or
 * refresh token — see {@code ImpersonateAccountController}'s own Javadoc for the full v1 scope
 * decision.
 *
 * <p><b>Reuses the same building blocks {@link OrganizationAuthorizationServerConfig} wires into
 * the real per-Organization issuer chain</b> ({@link OrganizationScopedJwkSource}, {@link
 * OrganizationRegisteredClientRepository#toRegisteredClient}, {@link
 * HashedTokenOAuth2AuthorizationService}) — all three are stateless wrappers over already-shared
 * singleton beans, safe to construct fresh instances of here rather than exposing them as
 * additional Spring beans (which would risk {@code RegisteredClientRepository} bean-type-ambiguity
 * against {@link PlatformRegisteredClientRepository}, the one existing
 * {@code @Repository}-annotated bean of that type — confirmed by reading its own source before this
 * class was written). {@link RefreshTokenRotationAuthenticationProvider} is this class's own
 * closest precedent for hand-building a token outside SAS's normal grant-type dispatch — this class
 * mirrors its {@code ACCESS_TOKEN} branch, minus the ID-token machinery that provider needs only
 * for the {@code REFRESH_TOKEN} grant's own {@code sid}/{@code auth_time} requirements.
 *
 * <p><b>Why a synthetic {@link AuthorizationServerContext} is pushed before minting:</b> both
 * {@link OrganizationScopedJwkSource} and the {@code iss} claim {@code JwtGenerator} itself writes
 * resolve "which Organization/issuer is this" from {@link AuthorizationServerContextHolder}, which
 * is normally populated per-request by SAS's own {@code AuthorizationServerContextFilter} from the
 * incoming {@code /o/{organizationId}/...} URL — this call happens on an unrelated {@code
 * /api/v1/admin/**} request thread, so nothing populates it. The pushed issuer string must exactly
 * equal what a real request through the tenant chain would resolve (same scheme/host/port, same
 * {@code {baseUrl}/o/{organizationId}} shape, ADR-0010 §5), or the minted token's own {@code iss}
 * claim would silently mismatch what {@code OrganizationJwtIssuerValidator} checks against at
 * verification time (e.g. the first real call to {@code /o/{organizationId}/userinfo} the caller
 * makes with it) — {@code baseUrl} is therefore derived by the controller from the actual incoming
 * admin request, the same physical deployment serving both surfaces, not a separately configured
 * value that could drift from it.
 */
// PMD.LongVariable: every flagged name below matches either a collaborating SAS/Spring Security
// type's own descriptive parameter name (tokenIssuanceLogger, accessTokenContext,
// generatedAccessToken, authorizationBuilder, authorizationService — same precedent
// OrganizationAuthorizationServerConfig/RefreshTokenRotationAuthenticationProvider's own identical
// suppressions already establish) or a public constant whose exact name matters for readability at
// its own call sites (TOKEN_EXCHANGE_GRANT_TYPE, ISSUER_PATH_PREFIX) — shortening any of them would
// make this class harder to compare against the SAS source it mirrors, not easier to read.
// java:S1075: ISSUER_PATH_PREFIX/TOKEN_ENDPOINT/JWK_SET_ENDPOINT are routes this deployment owns
// and serves itself (ADR-0010 §5's own fixed issuer shape), not an external URI a deployment
// should be able to independently repoint — same reasoning
// SocialLoginAuthenticationSuccessHandler's
// own identical suppression already documents for its TENANT_PATH_PREFIX. Making these configurable
// would be actively dangerous here, not just unnecessary: ISSUER_PATH_PREFIX must stay
// byte-for-byte
// identical to CurrentOrganizationContext's own hardcoded "/o/" prefix (what a token minted here
// must resolve back to on any future verification) and to OrganizationAuthorizationServerConfig's
// own securityMatcher/tokenEndpoint/jwkSetEndpoint literals — two independently configurable copies
// of the same structural constant could silently drift apart.
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.LongVariable",
  "java:S1075"
})
@Component
class ImpersonationTokenIssuer {

  // RFC 8693 (OAuth 2.0 Token Exchange) is the standard's own name for exactly this shape of
  // issuance — a token minted on behalf of one identity while a distinct actor performs the
  // exchange — the same real-world convention Auth0/Okta's own impersonation features use, not an
  // invented label. Never checked against registeredClient.getAuthorizationGrantTypes() (unlike
  // RefreshTokenRotationAuthenticationProvider's own REFRESH_TOKEN check) — this grant is issued by
  // an operator action, not requested by the client itself, so there is nothing on OAuthClient's
  // own
  // registration for it to be validated against.
  private static final AuthorizationGrantType TOKEN_EXCHANGE_GRANT_TYPE =
      new AuthorizationGrantType("urn:ietf:params:oauth:grant-type:token-exchange");

  private static final String ISSUER_PATH_PREFIX = "/o/";
  private static final String TOKEN_ENDPOINT = "/oauth2/token";
  private static final String JWK_SET_ENDPOINT = "/oauth2/jwks";

  private final OAuthClientRepository oauthClients;
  private final SigningKeyRepository signingKeys;
  private final OrganizationSigningKeyMaterialFactory keyMaterial;
  private final JdbcTemplate jdbcTemplate;
  private final BearerTokenHasher bearerTokenHasher;
  private final OAuth2TokenCustomizer<JwtEncodingContext> tokenIssuanceLogger;

  @SuppressWarnings("java:S107") // one parameter per collaborating port/bean — same rationale as
  // OrganizationAuthorizationServerConfig's own identical suppression for this same shape of
  // token-issuance wiring.
  /* package */ ImpersonationTokenIssuer(
      final OAuthClientRepository oauthClients,
      final SigningKeyRepository signingKeys,
      final OrganizationSigningKeyMaterialFactory keyMaterial,
      final JdbcTemplate jdbcTemplate,
      final BearerTokenHasher bearerTokenHasher,
      final OAuth2TokenCustomizer<JwtEncodingContext> tokenIssuanceLogger) {
    this.oauthClients = oauthClients;
    this.signingKeys = signingKeys;
    this.keyMaterial = keyMaterial;
    this.jdbcTemplate = jdbcTemplate;
    this.bearerTokenHasher = bearerTokenHasher;
    this.tokenIssuanceLogger = tokenIssuanceLogger;
  }

  /**
   * @param baseUrl this deployment's own scheme+host+port, as seen on the current admin request —
   *     see this class's own Javadoc for why it must come from there, not a fixed config value.
   * @throws ImpersonationClientNotFoundException if {@code clientId} doesn't resolve to a
   *     registered {@code OAuthClient}, or resolves to one belonging to a *different* Organization
   *     than the target Account's own (ADR-0010: masked identically to "not found", same
   *     cross-tenant discipline {@code OrganizationRegisteredClientRepository} already applies)
   * @throws ImpersonationScopeNotAllowedException if {@code requestedScopes} contains anything
   *     outside the resolved client's own {@code allowedScopes}
   */
  /* package */ ImpersonationToken mint(
      final AccountId accountId,
      final OrganizationId organizationId,
      final String clientId,
      final List<String> requestedScopes,
      final AuditActor actor,
      final String baseUrl) {
    final OAuthClient client = resolveClientOrThrow(clientId, organizationId);
    final Set<String> scopes = resolveScopesOrThrow(client, requestedScopes);
    final RegisteredClient registeredClient =
        OrganizationRegisteredClientRepository.toRegisteredClient(client);

    AuthorizationServerContextHolder.setContext(syntheticContext(baseUrl, organizationId));
    try {
      return mintUnderCurrentContext(accountId, registeredClient, scopes, actor);
    } finally {
      // Never leaves a synthetic context behind for any other code running on this same
      // (pooled, reused) request-handling thread afterward.
      AuthorizationServerContextHolder.resetContext();
    }
  }

  private OAuthClient resolveClientOrThrow(
      final String clientId, final OrganizationId organizationId) {
    return oauthClients
        .findByClientId(clientId)
        .filter(candidate -> candidate.organizationId().equals(organizationId.value()))
        .orElseThrow(() -> new ImpersonationClientNotFoundException(clientId));
  }

  // Two exits (default-to-full-allowed-scopes, or the throw below) plus the final return — same
  // "one exit per distinct outcome" rationale as every other admin-API-adjacent method in this
  // codebase with the identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static Set<String> resolveScopesOrThrow(
      final OAuthClient client, final List<String> requestedScopes) {
    if (requestedScopes == null || requestedScopes.isEmpty()) {
      return new LinkedHashSet<>(client.allowedScopes());
    }
    if (!client.allowedScopes().containsAll(requestedScopes)) {
      throw new ImpersonationScopeNotAllowedException(client.clientId());
    }
    return new LinkedHashSet<>(requestedScopes);
  }

  private static AuthorizationServerContext syntheticContext(
      final String baseUrl, final OrganizationId organizationId) {
    final String issuer = baseUrl + ISSUER_PATH_PREFIX + organizationId.value();
    // NOT .issuer(issuer) here: AuthorizationServerSettings.Builder.build() itself rejects a fixed
    // issuer when multipleIssuersAllowed(true) is also set (confirmed live — the real chain's own
    // settings never sets one either, for the same reason). Harmless either way for this synthetic
    // context: JwtGenerator reads the iss claim from AuthorizationServerContext.getIssuer() below
    // directly (confirmed by reading its bytecode), never from getAuthorizationServerSettings().
    final AuthorizationServerSettings settings =
        AuthorizationServerSettings.builder()
            .multipleIssuersAllowed(true)
            .tokenEndpoint(TOKEN_ENDPOINT)
            .jwkSetEndpoint(JWK_SET_ENDPOINT)
            .build();
    return new AuthorizationServerContext() {
      @Override
      public String getIssuer() {
        return issuer;
      }

      @Override
      public AuthorizationServerSettings getAuthorizationServerSettings() {
        return settings;
      }
    };
  }

  private ImpersonationToken mintUnderCurrentContext(
      final AccountId accountId,
      final RegisteredClient registeredClient,
      final Set<String> scopes,
      final AuditActor actor) {
    final JWKSource<SecurityContext> signingJwkSource =
        new OrganizationScopedJwkSource(signingKeys, keyMaterial);
    final JwtEncoder jwtEncoder = new NimbusJwtEncoder(signingJwkSource);
    final JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
    jwtGenerator.setJwtCustomizer(
        context -> {
          tokenIssuanceLogger.customize(context);
          addImpersonationClaims(context, actor);
        });

    // sub == the impersonated Account's own id, exactly as a real login's own principal would be
    // (SpringSecurityAuthenticatedSessionEstablisher's own convention) — the whole point of this
    // token is that a resource server can't tell it apart from one issued by a real login.
    final Authentication principal =
        UsernamePasswordAuthenticationToken.authenticated(
            accountId.value().toString(), null, List.of());

    final OAuth2TokenContext accessTokenContext =
        DefaultOAuth2TokenContext.builder()
            .registeredClient(registeredClient)
            .principal(principal)
            .authorizationServerContext(AuthorizationServerContextHolder.getContext())
            .authorizedScopes(scopes)
            .authorizationGrantType(TOKEN_EXCHANGE_GRANT_TYPE)
            .authorizationGrant(principal)
            .tokenType(OAuth2TokenType.ACCESS_TOKEN)
            .build();

    final OAuth2Token generatedAccessToken = jwtGenerator.generate(accessTokenContext);
    if (generatedAccessToken == null) {
      // Same "the token generator failed" shape RefreshTokenRotationAuthenticationProvider's own
      // identical branch guards against — mints under the exact same JwtGenerator/JwkSource
      // machinery, so a null result here means the same class of underlying failure (e.g. no
      // active signing key for this Organization) as it would there.
      throw new ImpersonationTokenGenerationFailedException();
    }

    final OAuth2Authorization.Builder authorizationBuilder =
        OAuth2Authorization.withRegisteredClient(registeredClient)
            .id(UUID.randomUUID().toString())
            .principalName(principal.getName())
            .authorizationGrantType(TOKEN_EXCHANGE_GRANT_TYPE)
            .authorizedScopes(scopes);
    final OAuth2AccessToken accessToken =
        buildAccessToken(authorizationBuilder, generatedAccessToken, scopes);

    // Revocable via /oauth2/revoke exactly like any other token this system issues (TD-SEC-003) —
    // scoped to a single-client repository holding only the one RegisteredClient already resolved
    // above, not OrganizationRegisteredClientRepository's own CurrentOrganizationContext-dependent
    // lookup (this thread's synthetic context is reset before this authorization row would ever be
    // reloaded from it — a later /oauth2/revoke call reaches it correctly instead via the real
    // per-request context that call's own org-scoped chain establishes).
    final OAuth2AuthorizationService authorizationService =
        new HashedTokenOAuth2AuthorizationService(
            new JdbcOAuth2AuthorizationService(
                jdbcTemplate, new SingleClientRegisteredClientRepository(registeredClient)),
            bearerTokenHasher);
    authorizationService.save(authorizationBuilder.build());

    return new ImpersonationToken(accessToken.getTokenValue(), accessToken.getExpiresAt(), scopes);
  }

  // RFC 8693 act claim: marks this token as issued on-behalf-of the impersonated Account while
  // acting-as the calling PlatformClient — the one structural difference between this token and one
  // a real login of the same Account would receive, so a resource server (or a future audit query)
  // can always tell the two apart. amr:["imp"] is this codebase's own marker, not an OIDC-standard
  // value (no ID token is minted here for a real amr claim to matter to), kept anyway for the same
  // "authentication context is visible in the token, not just in the audit log" reasoning
  // AuthenticationContextClaimsCustomizer already applies to a real login's own acr/amr.
  //
  // Real bug found and closed by this class's own integration test: Map.of(...)/List.of(...) return
  // package-private java.util.ImmutableCollections types — JdbcOAuth2AuthorizationService's own
  // Jackson-based claims serialization writes those out fine, but its PolymorphicTypeValidator
  // rejects them on READ-BACK (e.g. the very next /oauth2/revoke call), throwing a 500 from deep
  // inside SAS's own row-mapper. Same fix, same root cause, as
  // AuthenticationContextClaimsCustomizer's
  // own identical `new ArrayList<>(...)` for its amr claim — plain, mutable JDK collection types
  // the
  // validator's allowlist actually recognizes.
  // PMD.LawOfDemeter: context.getClaims() is the standard SAS/Spring Security extension point for
  // exactly this — same "there is no other way to reach it" reasoning TokenIssuanceEventLogger's
  // own identical suppression already establishes for this same JwtEncodingContext API shape.
  @SuppressWarnings("PMD.LawOfDemeter")
  private static void addImpersonationClaims(
      final JwtEncodingContext context, final AuditActor actor) {
    final Map<String, Object> act = new LinkedHashMap<>();
    act.put("sub", actor.id());
    act.put("type", actor.type().name());
    context.getClaims().claim("act", act).claim("amr", new ArrayList<>(List.of("imp")));
  }

  private static OAuth2AccessToken buildAccessToken(
      final OAuth2Authorization.Builder authorizationBuilder,
      final OAuth2Token generatedAccessToken,
      final Set<String> scopes) {
    final OAuth2AccessToken accessToken =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            generatedAccessToken.getTokenValue(),
            generatedAccessToken.getIssuedAt(),
            generatedAccessToken.getExpiresAt(),
            scopes);
    authorizationBuilder.token(
        accessToken,
        metadata -> {
          if (generatedAccessToken instanceof ClaimAccessor claimAccessor) {
            metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, claimAccessor.getClaims());
          }
          metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, false);
        });
    return accessToken;
  }

  /** {@code accessToken}, its expiry, and the scopes it actually carries. */
  /* package */ record ImpersonationToken(
      String accessToken, Instant expiresAt, Set<String> scopes) {}

  /**
   * A {@link RegisteredClientRepository} scoped to exactly the one {@link RegisteredClient} already
   * resolved by {@link #mint} — used only for this call's own {@code
   * JdbcOAuth2AuthorizationService} construction, deliberately not {@link
   * OrganizationRegisteredClientRepository} itself (see this class's own Javadoc on {@link
   * #mintUnderCurrentContext} for why).
   */
  private static final class SingleClientRegisteredClientRepository
      implements RegisteredClientRepository {

    private final RegisteredClient registeredClient;

    private SingleClientRegisteredClientRepository(final RegisteredClient registeredClient) {
      this.registeredClient = registeredClient;
    }

    @Override
    public void save(final RegisteredClient client) {
      throw new UnsupportedOperationException(
          "OAuthClient creation goes through RegisterOAuthClientUseCase, never through this SPI");
    }

    // Parameter name matches RegisteredClientRepository's own interface signature — kept as-is for
    // readability against the SPI it implements, same precedent as
    // OrganizationRegisteredClientRepository/PlatformRegisteredClientRepository's own findById.
    @SuppressWarnings("PMD.ShortVariable")
    @Override
    public RegisteredClient findById(final String id) {
      return registeredClient.getId().equals(id) ? registeredClient : null;
    }

    @Override
    public RegisteredClient findByClientId(final String clientId) {
      return registeredClient.getClientId().equals(clientId) ? registeredClient : null;
    }
  }
}
