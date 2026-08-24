package com.clavaris.app.infrastructure.config;

import java.util.function.UnaryOperator;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/**
 * TD-SEC-019 (closed): wraps a {@code JdbcOAuth2AuthorizationService} so every bearer token value
 * this system ever writes to {@code oauth2_authorization} is an HMAC-SHA256 digest ({@link
 * BearerTokenHasher}), never the plaintext value SAS's own class would otherwise persist — the same
 * hash-not-plaintext principle {@code data-model.md} §2 already states for {@code
 * password_credentials}/{@code refresh_tokens}/{@code verification_tokens}, extended to the one
 * table in this schema that principle didn't reach yet because it isn't this project's own design
 * (it is SAS's, ADR-0003).
 *
 * <p><b>What actually gets hashed, and why only those three:</b> {@link OAuth2AuthorizationCode},
 * {@link OAuth2AccessToken}, and {@link OidcIdToken} — the only three token types this codebase's
 * own authorization-server wiring ever attaches to an {@link OAuth2Authorization} it saves here
 * (confirmed by reading {@code OrganizationAuthorizationServerConfig}/{@code
 * PlatformAuthorizationServerConfig}/{@code RefreshTokenRotationAuthenticationProvider}, none of
 * which ever call {@code .refreshToken(...)}/{@code .token(OAuth2UserCode...)}/{@code
 * .token(OAuth2DeviceCode...)} on the builder). Refresh tokens never touch this table at all —
 * BR-ID-03's own {@code refresh_tokens} table is the sole source of truth for those, already
 * hash-stored, fully decoupled from {@link OAuth2AuthorizationService#findByToken} (see {@code
 * RefreshTokenRotationAuthenticationProvider}'s own Javadoc). {@code state} is a plain request
 * attribute, not a token column this class touches.
 *
 * <p><b>Why {@code findByToken} has to hash then un-hash, not just hash:</b> confirmed by reading
 * SAS's own 7.1.0 source for both real callers of this method in this codebase's reachable call
 * graph — {@code OAuth2TokenRevocationAuthenticationProvider} and {@code
 * OAuth2TokenIntrospectionAuthenticationProvider} — each immediately calls {@code
 * authorization.getToken(<the exact value it just searched with>)}, an exact-string-equals lookup
 * against every stored token's own {@code getTokenValue()}, and {@code Assert.notNull}s the result.
 * A hashed value never equals the raw value the caller already holds, so the one token slot that
 * matched the search gets its raw form restored in memory before this method returns — the same
 * value the caller already possesses, never a new disclosure, and never re-persisted in that form
 * unless {@link #save} is called again, which re-hashes it right back before it reaches Postgres.
 */
final class HashedTokenOAuth2AuthorizationService implements OAuth2AuthorizationService {

  private final OAuth2AuthorizationService delegate;
  private final BearerTokenHasher hasher;

  /* package */ HashedTokenOAuth2AuthorizationService(
      final OAuth2AuthorizationService delegate, final BearerTokenHasher hasher) {
    this.delegate = delegate;
    this.hasher = hasher;
  }

  @Override
  public void save(final OAuth2Authorization authorization) {
    delegate.save(hashBearerTokens(authorization));
  }

  @Override
  public void remove(final OAuth2Authorization authorization) {
    // Id-keyed (DELETE ... WHERE id = ?) — the argument's own token values never reach the
    // delegate's SQL, so there is nothing here to hash.
    delegate.remove(authorization);
  }

  // Parameter name matches OAuth2AuthorizationService's own interface signature — kept as-is for
  // readability against the SPI it implements, same precedent as
  // OrganizationRegisteredClientRepository/PlatformRegisteredClientRepository's own findById.
  @SuppressWarnings("PMD.ShortVariable")
  @Override
  public OAuth2Authorization findById(final String id) {
    // Id-keyed, same reasoning as remove() — every consumer of a findById result in this
    // codebase's reachable call graph (JdbcOAuth2AuthorizationService's own save()-before-insert
    // check, RegisteredClientRepository reload paths) only ever reads metadata/ids, never compares
    // a token's raw value, so — unlike findByToken — no restoration step is needed here.
    return delegate.findById(id);
  }

  // Two early returns (the never-hashed passthrough, and the null-on-a-genuine-miss case) read
  // clearer as guard clauses than nested inside a single trailing return — same precedent as
  // ActivateSigningKeyForOrganizationService/ActivatePlatformSigningKeyService's own identical
  // suppression for an early-return no-op path.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public OAuth2Authorization findByToken(final String token, final OAuth2TokenType tokenType) {
    if (isNeverHashed(tokenType)) {
      // STATE / REFRESH_TOKEN / USER_CODE / DEVICE_CODE: this codebase never populates
      // refresh_token_value/user_code_value/device_code_value (see this class's own Javadoc), and
      // `state` is stored as a plain attribute, never hashed on the way in — passing the raw token
      // straight through matches JdbcOAuth2AuthorizationService's own unmodified column semantics
      // for exactly these columns.
      return delegate.findByToken(token, tokenType);
    }

    final String hashedToken = hasher.hash(token);
    OAuth2Authorization found = delegate.findByToken(hashedToken, tokenType);
    if (found == null && tokenType == null) {
      // The real callers that matter today (OAuth2TokenRevocationAuthenticationProvider,
      // OAuth2TokenIntrospectionAuthenticationProvider) always pass a null tokenType — they don't
      // know in advance which token slot a presented bearer value belongs to. The hashed attempt
      // above covers the common case (an access token, authorization code, or ID token — the only
      // three columns this service ever hashes); this fallback is defense in depth for a
      // currently-unreachable path presenting a raw state/refresh_token/user_code/device_code
      // value through the same null-typed call — cheap, since it only runs on an outright miss.
      found = delegate.findByToken(token, null);
    }
    if (found == null) {
      return null;
    }
    return restoreRawTokenValue(found, new RawToken(token, hashedToken));
  }

  private OAuth2Authorization hashBearerTokens(final OAuth2Authorization authorization) {
    final OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorization);
    replaceToken(
        builder,
        authorization,
        OAuth2AuthorizationCode.class,
        code ->
            new OAuth2AuthorizationCode(
                hasher.hash(code.getTokenValue()), code.getIssuedAt(), code.getExpiresAt()));
    replaceToken(
        builder,
        authorization,
        OAuth2AccessToken.class,
        accessToken ->
            new OAuth2AccessToken(
                accessToken.getTokenType(),
                hasher.hash(accessToken.getTokenValue()),
                accessToken.getIssuedAt(),
                accessToken.getExpiresAt(),
                accessToken.getScopes()));
    replaceToken(
        builder,
        authorization,
        OidcIdToken.class,
        idToken ->
            new OidcIdToken(
                hasher.hash(idToken.getTokenValue()),
                idToken.getIssuedAt(),
                idToken.getExpiresAt(),
                idToken.getClaims()));
    return builder.build();
  }

  private static <T extends OAuth2Token> void replaceToken(
      final OAuth2Authorization.Builder builder,
      final OAuth2Authorization source,
      final Class<T> tokenClass,
      final UnaryOperator<T> hashedCopy) {
    final OAuth2Authorization.Token<T> existing = source.getToken(tokenClass);
    if (existing != null) {
      builder.token(
          hashedCopy.apply(existing.getToken()),
          metadata -> metadata.putAll(existing.getMetadata()));
    }
  }

  // Small private record purely to carry both forms of the searched-for value from findByToken
  // into restoreRawTokenValue without a second hasher.hash(...) call at the restoration site.
  private record RawToken(String raw, String hashed) {}

  private static OAuth2Authorization restoreRawTokenValue(
      final OAuth2Authorization authorization, final RawToken token) {
    final OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorization);
    final boolean restored =
        restoreIfHashMatches(
                builder,
                authorization,
                OAuth2AuthorizationCode.class,
                token,
                code ->
                    new OAuth2AuthorizationCode(
                        token.raw(), code.getIssuedAt(), code.getExpiresAt()))
            || restoreIfHashMatches(
                builder,
                authorization,
                OAuth2AccessToken.class,
                token,
                accessToken ->
                    new OAuth2AccessToken(
                        accessToken.getTokenType(),
                        token.raw(),
                        accessToken.getIssuedAt(),
                        accessToken.getExpiresAt(),
                        accessToken.getScopes()))
            || restoreIfHashMatches(
                builder,
                authorization,
                OidcIdToken.class,
                token,
                idToken ->
                    new OidcIdToken(
                        token.raw(),
                        idToken.getIssuedAt(),
                        idToken.getExpiresAt(),
                        idToken.getClaims()));
    return restored ? builder.build() : authorization;
  }

  // PMD.LawOfDemeter: existing.getToken().getTokenValue() walks OAuth2Authorization.Token's own
  // two-level accessor shape — the SPI's own contract for reaching a stored token's value, not an
  // organically grown chain (same reasoning as
  // SpringSecurityPlatformAuthenticatedSessionEstablisher's
  // request.getSession() suppression for a standard framework accessor shape). PMD.OnlyOneReturn:
  // an early "no match" return reads clearer here than threading a match/no-match flag through to
  // one trailing return, same precedent as this class's own findByToken above.
  @SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
  private static <T extends OAuth2Token> boolean restoreIfHashMatches(
      final OAuth2Authorization.Builder builder,
      final OAuth2Authorization source,
      final Class<T> tokenClass,
      final RawToken token,
      final UnaryOperator<T> rawCopy) {
    final OAuth2Authorization.Token<T> existing = source.getToken(tokenClass);
    if (existing == null || !existing.getToken().getTokenValue().equals(token.hashed())) {
      return false;
    }
    builder.token(
        rawCopy.apply(existing.getToken()), metadata -> metadata.putAll(existing.getMetadata()));
    return true;
  }

  private static boolean isNeverHashed(final OAuth2TokenType tokenType) {
    return tokenType != null
        && (OAuth2ParameterNames.STATE.equals(tokenType.getValue())
            || OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)
            || OAuth2ParameterNames.USER_CODE.equals(tokenType.getValue())
            || OAuth2ParameterNames.DEVICE_CODE.equals(tokenType.getValue()));
  }
}
