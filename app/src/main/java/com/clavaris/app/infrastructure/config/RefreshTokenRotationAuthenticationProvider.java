package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.rotaterefreshtoken.InvalidRefreshTokenException;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.RefreshTokenReuseDetectedException;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.RequestedScopeExceedsAuthorizedScopeException;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.RotateRefreshTokenCommand;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.RotateRefreshTokenResult;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.RotateRefreshTokenUseCase;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * BR-ID-03: replaces SAS's own {@code OAuth2RefreshTokenAuthenticationProvider} for the refresh
 * grant entirely — wired via {@code .tokenEndpoint(tokenEndpoint ->
 * tokenEndpoint.authenticationProviders(providers -> {...}))} in {@link
 * OrganizationAuthorizationServerConfig}, the same provider-swap extension point already used there
 * for {@code ClientSecretAuthenticationProvider}'s password encoder. Not a subclass — the stock
 * class is {@code final} (confirmed by reading its own source) — and not a delegating wrapper
 * either: single-use rotation with reuse detection has no equivalent in the stock class's own
 * validation (it only supports plain reuse of the same value, or blind overwrite-on-rotation with
 * no memory of what was rotated away), so there is nothing left to delegate to for the one thing
 * this class actually exists to do.
 *
 * <p>Reuses the framework's own token-generation machinery for the access/ID token exactly the way
 * the stock provider does (same {@link OAuth2TokenGenerator}, same {@link
 * DefaultOAuth2TokenContext} shape, read directly from the stock class's own 7.1.0 source) — the
 * only real substitution is refresh-token validation itself: {@link RotateRefreshTokenUseCase}
 * against {@code refresh_tokens} (hash-stored, BR-ID-03's own chain), never {@link
 * OAuth2AuthorizationService#findByToken}.
 *
 * <p><b>Known, deliberate gap, not an oversight:</b> every rotation here saves a brand-new {@code
 * OAuth2Authorization} row (a fresh random id) rather than updating the previous grant's row in
 * place the way the stock provider does via {@code OAuth2Authorization.from(existingAuthorization)}
 * (confirmed by reading its source) — this class deliberately never loads that previous row at all
 * (BR-ID-03's refresh-token validation is fully decoupled from {@code oauth2_authorization}). Each
 * old row's access/ID token still naturally expires (SAS's own default {@code
 * accessTokenTimeToLive}), but the row itself is never removed — a long-lived session with many
 * refreshes accumulates one {@code oauth2_authorization} row per refresh, unbounded, tracked as
 * TD-ARCH-005 rather than silently left undocumented.
 */
// This class deliberately mirrors SAS's own OAuth2RefreshTokenAuthenticationProvider variable-for-
// variable in several spots (registeredClient, tokenContextBuilder, authorizationBuilder,
// generatedAccessToken, ...) so it stays directly comparable against the stock class's own source
// it's modeled on — renaming them shorter to satisfy PMD would make that comparison harder, not
// the class actually clearer. Same reasoning as the sibling AuthorizationServerConfig classes'
// own ExcessiveImports suppression for "wiring together many distinct SAS protocol types."
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.LongVariable"})
final class RefreshTokenRotationAuthenticationProvider implements AuthenticationProvider {

  private static final String ERROR_URI =
      "https://datatracker.ietf.org/doc/html/rfc6749#section-5.2";
  private static final OAuth2TokenType ID_TOKEN_TOKEN_TYPE =
      new OAuth2TokenType(OidcParameterNames.ID_TOKEN);

  private final OAuth2AuthorizationService authorizationService;
  private final OAuth2TokenGenerator<?> tokenGenerator;
  private final RotateRefreshTokenUseCase rotateRefreshToken;

  /* package */ RefreshTokenRotationAuthenticationProvider(
      final OAuth2AuthorizationService authorizationService,
      final OAuth2TokenGenerator<?> tokenGenerator,
      final RotateRefreshTokenUseCase rotateRefreshToken) {
    this.authorizationService = authorizationService;
    this.tokenGenerator = tokenGenerator;
    this.rotateRefreshToken = rotateRefreshToken;
  }

  // AvoidUncheckedExceptionsInSignatures: `throws AuthenticationException` is mandated by the
  // AuthenticationProvider interface itself, not a choice this method makes.
  @SuppressWarnings("PMD.AvoidUncheckedExceptionsInSignatures")
  @Override
  public Authentication authenticate(final Authentication authentication)
      throws AuthenticationException {
    final OAuth2RefreshTokenAuthenticationToken refreshTokenAuthentication =
        (OAuth2RefreshTokenAuthenticationToken) authentication;
    final OAuth2ClientAuthenticationToken clientPrincipal =
        authenticatedClientOrThrowInvalidClient(refreshTokenAuthentication);
    final RegisteredClient registeredClient = clientPrincipal.getRegisteredClient();

    if (!registeredClient
        .getAuthorizationGrantTypes()
        .contains(AuthorizationGrantType.REFRESH_TOKEN)) {
      throw new OAuth2AuthenticationException(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT);
    }

    // RFC 6749 §6's own scope-not-exceeded check runs inside RotateRefreshTokenService's single
    // transaction, before any mutation — not here, after the fact. See
    // RequestedScopeExceedsAuthorizedScopeException's own Javadoc for the real bug that ordering
    // fixes: checking this in the infrastructure layer, after the use case had already rotated the
    // token, meant an over-scoped request silently consumed the presented refresh token and still
    // returned an error.
    final RotateRefreshTokenResult result = rotate(refreshTokenAuthentication, registeredClient);
    final Set<String> scopes = new LinkedHashSet<>(result.authorizedScopes());

    // OIDC Core §2: auth_time must reflect the original login, not this rotation — carried
    // forward from Session.createdAt(), not Instant.now().
    final Authentication principal =
        UsernamePasswordAuthenticationToken.authenticated(
            result.accountId().value().toString(),
            null,
            List.of(
                FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                    .issuedAt(result.sessionCreatedAt())
                    .build()));

    final DefaultOAuth2TokenContext.Builder tokenContextBuilder =
        DefaultOAuth2TokenContext.builder()
            .registeredClient(registeredClient)
            .principal(principal)
            .authorizationServerContext(AuthorizationServerContextHolder.getContext())
            .authorizedScopes(scopes)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .authorizationGrant(refreshTokenAuthentication);

    final OAuth2Authorization.Builder authorizationBuilder =
        OAuth2Authorization.withRegisteredClient(registeredClient)
            .id(UUID.randomUUID().toString())
            .principalName(principal.getName())
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .authorizedScopes(scopes);

    final OAuth2TokenContext accessTokenContext =
        tokenContextBuilder.tokenType(OAuth2TokenType.ACCESS_TOKEN).build();
    final OAuth2Token generatedAccessToken = tokenGenerator.generate(accessTokenContext);
    if (generatedAccessToken == null) {
      throw new OAuth2AuthenticationException(
          new OAuth2Error(
              OAuth2ErrorCodes.SERVER_ERROR,
              "The token generator failed to generate the access token.",
              ERROR_URI));
    }
    final OAuth2AccessToken accessToken =
        buildAccessToken(authorizationBuilder, generatedAccessToken, accessTokenContext);

    OidcIdToken idToken = null;
    if (scopes.contains(OidcScopes.OPENID)) {
      // JwtGenerator's own REFRESH_TOKEN-grant branch (confirmed by reading its 7.1.0 source)
      // never calls getAuthenticationTime(principal) the way it does for a fresh
      // AUTHORIZATION_CODE-grant ID token — it insists on reading sid/auth_time off a
      // *previously-issued* OidcIdToken already present in context.getAuthorization(), asserting
      // non-null. The stock provider satisfies this by building its new OAuth2Authorization via
      // OAuth2Authorization.from(existingAuthorization), inheriting the original code exchange's
      // real ID token; this provider deliberately never loads that old authorization (BR-ID-03's
      // refresh-token validation is fully decoupled from oauth2_authorization), so there is
      // nothing to inherit. A minimal placeholder carrying exactly the two claims that branch
      // reads — sid, auth_time (a java.util.Date, matching JwtGenerator's own
      // Date.from(...) convention exactly) — satisfies it correctly rather than working around
      // it; the placeholder is immediately superseded in authorizationBuilder by the real,
      // freshly-generated ID token below (OAuth2Authorization.Builder.token(T) keys by class).
      // java:S2143/PMD.ReplaceJavaUtilDate ("use java.time") don't apply here: JwtGenerator's own
      // source reads this claim back with `Date authTimeClaim = currentIdToken.getClaim(...)` —
      // an unchecked cast keyed on the claim's stored runtime type, so it must literally be a
      // java.util.Date instance (matching what a real ID token's own getAuthenticationTime()
      // already stores via Date.from(...)), not this codebase's own preferred java.time type. A
      // local variable, not an inline NOSONAR, so the suppression follows this codebase's own
      // established convention (java:S107 elsewhere) rather than a NOSONAR comment, which this
      // SonarCloud project may not honor as a general analysis-scope setting.
      @SuppressWarnings({"java:S2143", "PMD.ReplaceJavaUtilDate"})
      final Date authTimeClaimValue = Date.from(result.sessionCreatedAt());
      final OidcIdToken priorIdTokenPlaceholder =
          new OidcIdToken(
              "placeholder-never-served-to-a-client",
              Instant.now(),
              Instant.now().plusSeconds(1),
              Map.of(
                  "sid",
                  result.sessionId().toString(),
                  IdTokenClaimNames.AUTH_TIME,
                  authTimeClaimValue));
      authorizationBuilder.token(priorIdTokenPlaceholder);

      final OAuth2TokenContext idTokenContext =
          tokenContextBuilder
              .tokenType(ID_TOKEN_TOKEN_TYPE)
              .authorization(authorizationBuilder.build())
              .build();
      final OAuth2Token generatedIdToken = tokenGenerator.generate(idTokenContext);
      if (!(generatedIdToken instanceof Jwt jwt)) {
        throw new OAuth2AuthenticationException(
            new OAuth2Error(
                OAuth2ErrorCodes.SERVER_ERROR,
                "The token generator failed to generate the ID token.",
                ERROR_URI));
      }
      idToken =
          new OidcIdToken(
              jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getClaims());
      final OidcIdToken finalIdToken = idToken;
      authorizationBuilder.token(
          idToken,
          metadata ->
              metadata.put(
                  OAuth2Authorization.Token.CLAIMS_METADATA_NAME, finalIdToken.getClaims()));
    }

    // TD-SEC-003: the same real, persistent JdbcOAuth2AuthorizationService every other token this
    // system issues already goes through — the new access/ID token this rotation just minted is
    // revocable via /oauth2/revoke exactly like any other, even though the refresh token itself
    // never touches this table (BR-ID-03's own source of truth is refresh_tokens).
    authorizationService.save(authorizationBuilder.build());

    final OAuth2RefreshToken newRefreshToken =
        new OAuth2RefreshToken(result.newRawToken(), Instant.now(), result.newExpiresAt());

    final Map<String, Object> additionalParameters =
        idToken == null ? Map.of() : Map.of(OidcParameterNames.ID_TOKEN, idToken.getTokenValue());

    return new OAuth2AccessTokenAuthenticationToken(
        registeredClient, clientPrincipal, accessToken, newRefreshToken, additionalParameters);
  }

  // BR-ID-03: InvalidRefreshTokenException and RefreshTokenReuseDetectedException both map to the
  // same RFC 6749 §5.2 invalid_grant here — deliberately, per both exceptions' own Javadoc, so a
  // presenter can never distinguish "reuse detected, everything just got revoked" from "this token
  // was simply wrong," the same anti-enumeration principle InvalidCredentialsException applies to
  // login.
  private RotateRefreshTokenResult rotate(
      final OAuth2RefreshTokenAuthenticationToken refreshTokenAuthentication,
      final RegisteredClient registeredClient) {
    try {
      return rotateRefreshToken.handle(
          new RotateRefreshTokenCommand(
              refreshTokenAuthentication.getRefreshToken(),
              List.copyOf(refreshTokenAuthentication.getScopes()),
              Instant.now().plus(registeredClient.getTokenSettings().getRefreshTokenTimeToLive())));
    } catch (final InvalidRefreshTokenException | RefreshTokenReuseDetectedException _) {
      throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
    } catch (final RequestedScopeExceedsAuthorizedScopeException _) {
      throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_SCOPE);
    }
  }

  @Override
  public boolean supports(final Class<?> authentication) {
    return OAuth2RefreshTokenAuthenticationToken.class.isAssignableFrom(authentication);
  }

  // Own equivalent of SAS's package-private OAuth2AuthenticationProviderUtils (confirmed by
  // reading its 7.1.0 source — not public, and this provider lives in a different package on
  // purpose, so reaching into that class's own package wasn't an option worth taking).
  @SuppressWarnings("PMD.LawOfDemeter") // Authentication.getPrincipal() is the SPI's own contract
  private static OAuth2ClientAuthenticationToken authenticatedClientOrThrowInvalidClient(
      final Authentication authentication) {
    final Object principal = authentication.getPrincipal();
    if (principal instanceof OAuth2ClientAuthenticationToken clientAuth
        && clientAuth.isAuthenticated()) {
      return clientAuth;
    }
    throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
  }

  private static OAuth2AccessToken buildAccessToken(
      final OAuth2Authorization.Builder authorizationBuilder,
      final OAuth2Token generatedAccessToken,
      final OAuth2TokenContext accessTokenContext) {
    final OAuth2AccessToken accessToken =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            generatedAccessToken.getTokenValue(),
            generatedAccessToken.getIssuedAt(),
            generatedAccessToken.getExpiresAt(),
            accessTokenContext.getAuthorizedScopes());
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
}
