package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * TD-SEC-019: exercises {@link HashedTokenOAuth2AuthorizationService} against a mocked delegate —
 * the real Postgres-backed proof that nothing at rest is ever plaintext, and that {@code
 * /oauth2/revoke} still works end to end against the hashed data, lives in the integration suite
 * ({@code PlatformTokenIssuanceIntegrationTest}, {@code AuthorizationCodeFlowIntegrationTest}).
 * This class's job is the narrower one: prove the wrapper's own hash-on-save /
 * hash-then-restore-on-findByToken logic is correct in isolation, including the branches a real
 * end-to-end HTTP flow can't easily force (a never-hashed token type, a hashed-lookup miss).
 */
class HashedTokenOAuth2AuthorizationServiceTest {

  private static final String SECRET = "a-test-hmac-secret";
  private static final BearerTokenHasher HASHER = new BearerTokenHasher(SECRET);

  private final OAuth2AuthorizationService delegate = mock(OAuth2AuthorizationService.class);
  private final HashedTokenOAuth2AuthorizationService service =
      new HashedTokenOAuth2AuthorizationService(delegate, HASHER);

  @Test
  void saveHashesTheAuthorizationCodeAccessTokenAndIdTokenValuesBeforeDelegating() {
    OAuth2Authorization authorization =
        authorizationWithAllThreeTokens("raw-code", "raw-access-token", "raw-id-token");

    service.save(authorization);

    OAuth2Authorization saved = capturedSavedAuthorization();
    assertThat(saved.getToken(OAuth2AuthorizationCode.class).getToken().getTokenValue())
        .isEqualTo(HASHER.hash("raw-code"))
        .isNotEqualTo("raw-code");
    assertThat(saved.getToken(OAuth2AccessToken.class).getToken().getTokenValue())
        .isEqualTo(HASHER.hash("raw-access-token"))
        .isNotEqualTo("raw-access-token");
    assertThat(saved.getToken(OidcIdToken.class).getToken().getTokenValue())
        .isEqualTo(HASHER.hash("raw-id-token"))
        .isNotEqualTo("raw-id-token");
  }

  @Test
  void savePreservesEveryNonTokenAttributeUnchanged() {
    OAuth2Authorization authorization =
        authorizationWithAllThreeTokens("raw-code", "raw-access-token", "raw-id-token");

    service.save(authorization);

    OAuth2Authorization saved = capturedSavedAuthorization();
    assertThat(saved.getId()).isEqualTo(authorization.getId());
    assertThat(saved.getPrincipalName()).isEqualTo(authorization.getPrincipalName());
    assertThat(saved.getRegisteredClientId()).isEqualTo(authorization.getRegisteredClientId());
    assertThat(saved.getAuthorizationGrantType())
        .isEqualTo(authorization.getAuthorizationGrantType());
    assertThat(saved.getAuthorizedScopes()).isEqualTo(authorization.getAuthorizedScopes());
    // Metadata carried alongside the access token (issuer-set, not this class's concern) must
    // survive the rebuild untouched — only the token *value* changes.
    assertThat(saved.getToken(OAuth2AccessToken.class).getMetadata())
        .isEqualTo(authorization.getToken(OAuth2AccessToken.class).getMetadata());
  }

  @Test
  void saveOnlyHashesWhicheverOfTheThreeTokenTypesAreActuallyPresent() {
    // The realistic client_credentials-grant shape: an access token, no authorization code, no ID
    // token (no openid scope) — this must not throw on the two absent token types.
    RegisteredClient client = registeredClient();
    OAuth2Authorization authorization =
        OAuth2Authorization.withRegisteredClient(client)
            .id(UUID.randomUUID().toString())
            .principalName("a-client-id")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .token(
                new OAuth2AccessToken(
                    TokenType.BEARER,
                    "raw-access-token",
                    Instant.now(),
                    Instant.now().plusSeconds(3600)))
            .build();

    service.save(authorization);

    OAuth2Authorization saved = capturedSavedAuthorization();
    assertThat(saved.getToken(OAuth2AuthorizationCode.class)).isNull();
    assertThat(saved.getToken(OidcIdToken.class)).isNull();
    assertThat(saved.getToken(OAuth2AccessToken.class).getToken().getTokenValue())
        .isEqualTo(HASHER.hash("raw-access-token"));
  }

  @Test
  void removePassesTheExactSameAuthorizationInstanceThroughUnchanged() {
    // Id-keyed delete — nothing to hash, nothing to rebuild (this class's own Javadoc).
    OAuth2Authorization authorization =
        authorizationWithAllThreeTokens("raw-code", "raw-access-token", "raw-id-token");

    service.remove(authorization);

    verify(delegate).remove(authorization);
  }

  @Test
  void findByIdReturnsExactlyWhateverTheDelegateReturnsUnmodified() {
    OAuth2Authorization stored =
        authorizationWithAllThreeTokens(
            HASHER.hash("raw-code"), HASHER.hash("raw-access-token"), HASHER.hash("raw-id-token"));
    when(delegate.findById("some-id")).thenReturn(stored);

    OAuth2Authorization found = service.findById("some-id");

    // Deliberately isSameAs, not isEqualTo — findById never rebuilds, so the identity itself
    // must be untouched, not merely an equal copy.
    assertThat(found).isSameAs(stored);
  }

  @Test
  void findByTokenHashesTheSearchValueAndRestoresTheRawValueOnAHit() {
    String rawAccessToken = "a-real-presented-access-token";
    String hashedAccessToken = HASHER.hash(rawAccessToken);
    OAuth2Authorization storedWithHashedValue =
        authorizationWithAllThreeTokens(
            HASHER.hash("raw-code"), hashedAccessToken, HASHER.hash("raw-id-token"));
    when(delegate.findByToken(hashedAccessToken, OAuth2TokenType.ACCESS_TOKEN))
        .thenReturn(storedWithHashedValue);

    OAuth2Authorization found = service.findByToken(rawAccessToken, OAuth2TokenType.ACCESS_TOKEN);

    // SAS's own OAuth2TokenRevocationAuthenticationProvider/
    // OAuth2TokenIntrospectionAuthenticationProvider immediately do
    // authorization.getToken(rawPresentedToken) and Assert.notNull the result — this is the
    // exact call that must succeed.
    assertThat(found).isNotNull();
    assertThat(found.getToken(OAuth2AccessToken.class).getToken().getTokenValue())
        .isEqualTo(rawAccessToken);
    // The other two token slots were not part of this search and must be left exactly as the
    // delegate returned them — still hashed, not incidentally restored too.
    assertThat(found.getToken(OAuth2AuthorizationCode.class).getToken().getTokenValue())
        .isEqualTo(HASHER.hash("raw-code"));
  }

  @Test
  void findByTokenReturnsNullOnAGenuineMissWithoutFallingBackForATypedSearch() {
    when(delegate.findByToken(any(), eq(OAuth2TokenType.ACCESS_TOKEN))).thenReturn(null);

    OAuth2Authorization found = service.findByToken("no-such-token", OAuth2TokenType.ACCESS_TOKEN);

    assertThat(found).isNull();
    // A typed search already knows which column it means — falling back to a second, raw-value
    // lookup here would only mask a genuine miss, not recover a real one.
    verify(delegate, never()).findByToken(eq("no-such-token"), isNull());
  }

  @Test
  void findByTokenFallsBackToARawValueLookupOnAnUntypedMissOnly() {
    // The one real caller shape that matters (OAuth2TokenRevocationAuthenticationProvider /
    // OAuth2TokenIntrospectionAuthenticationProvider, per this class's own Javadoc): tokenType is
    // null because the caller doesn't know in advance which column the presented value belongs
    // to. On a hashed-lookup miss, this must retry with the raw value before giving up.
    OAuth2Authorization storedByRawState =
        authorizationWithAllThreeTokens(
            HASHER.hash("raw-code"), HASHER.hash("raw-access-token"), HASHER.hash("raw-id-token"));
    when(delegate.findByToken(HASHER.hash("some-state-value"), null)).thenReturn(null);
    when(delegate.findByToken("some-state-value", null)).thenReturn(storedByRawState);

    OAuth2Authorization found = service.findByToken("some-state-value", null);

    assertThat(found).isNotNull();
    verify(delegate).findByToken("some-state-value", null);
  }

  @Test
  void findByTokenPassesStateAndRefreshTokenSearchesThroughUnhashed() {
    OAuth2TokenType stateType = new OAuth2TokenType(OAuth2ParameterNames.STATE);
    when(delegate.findByToken("a-raw-state-value", stateType)).thenReturn(null);

    service.findByToken("a-raw-state-value", stateType);

    // The value reaching the delegate must be the exact raw value — proof the hasher was never
    // applied to it at all, not merely that some hash happened to match.
    verify(delegate).findByToken("a-raw-state-value", stateType);

    when(delegate.findByToken("a-raw-refresh-token", OAuth2TokenType.REFRESH_TOKEN))
        .thenReturn(null);

    service.findByToken("a-raw-refresh-token", OAuth2TokenType.REFRESH_TOKEN);

    verify(delegate).findByToken("a-raw-refresh-token", OAuth2TokenType.REFRESH_TOKEN);
  }

  private OAuth2Authorization capturedSavedAuthorization() {
    ArgumentCaptor<OAuth2Authorization> captor = ArgumentCaptor.forClass(OAuth2Authorization.class);
    verify(delegate).save(captor.capture());
    return captor.getValue();
  }

  private static OAuth2Authorization authorizationWithAllThreeTokens(
      final String codeValue, final String accessTokenValue, final String idTokenValue) {
    RegisteredClient client = registeredClient();
    Instant now = Instant.now();
    return OAuth2Authorization.withRegisteredClient(client)
        .id(UUID.randomUUID().toString())
        .principalName("an-account-id")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizedScopes(Set.of("openid"))
        .token(new OAuth2AuthorizationCode(codeValue, now, now.plusSeconds(300)))
        .token(
            new OAuth2AccessToken(
                TokenType.BEARER, accessTokenValue, now, now.plusSeconds(3600), Set.of("openid")),
            metadata -> metadata.put("some-metadata-key", "some-metadata-value"))
        .token(
            new OidcIdToken(
                idTokenValue,
                now,
                now.plusSeconds(3600),
                Map.of(IdTokenClaimNames.SUB, "an-account-id")))
        .build();
  }

  private static RegisteredClient registeredClient() {
    return RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId("a-client-id")
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .redirectUri("https://example.com/callback")
        .scope("openid")
        .build();
  }
}
