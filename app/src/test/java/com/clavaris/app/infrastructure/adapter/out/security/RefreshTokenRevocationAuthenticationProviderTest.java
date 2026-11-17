package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.revokerefreshtoken.RevokeRefreshTokenCommand;
import com.clavaris.identity.application.usecase.revokerefreshtoken.RevokeRefreshTokenUseCase;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

/**
 * TD-SEC-100: the provider that makes {@code /oauth2/revoke} reach refresh tokens. It acts only for
 * an authenticated client on an Organization's endpoint, and hands everything it does not revoke on
 * to the stock provider by answering {@code null}.
 */
class RefreshTokenRevocationAuthenticationProviderTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private final RevokeRefreshTokenUseCase revoke = mock(RevokeRefreshTokenUseCase.class);
  private final RefreshTokenRevocationAuthenticationProvider provider =
      new RefreshTokenRevocationAuthenticationProvider(revoke);

  @AfterEach
  void resetContext() {
    AuthorizationServerContextHolder.resetContext();
  }

  private static void setIssuer(final String issuer) {
    AuthorizationServerContextHolder.setContext(
        new AuthorizationServerContext() {
          @Override
          public String getIssuer() {
            return issuer;
          }

          @Override
          public AuthorizationServerSettings getAuthorizationServerSettings() {
            return null;
          }
        });
  }

  private static OAuth2ClientAuthenticationToken authenticatedClient() {
    OAuth2ClientAuthenticationToken client = mock(OAuth2ClientAuthenticationToken.class);
    when(client.isAuthenticated()).thenReturn(true);
    return client;
  }

  private static OAuth2TokenRevocationAuthenticationToken request(final Authentication client) {
    return new OAuth2TokenRevocationAuthenticationToken("raw-refresh-token", client, null);
  }

  @Test
  void aRefreshTokenOfThisOrganizationIsRevokedAndTheRequestIsComplete() {
    setIssuer("https://clavaris.example.com/o/" + ORGANIZATION_ID);
    OAuth2ClientAuthenticationToken client = authenticatedClient();
    when(revoke.handle(any())).thenReturn(true);

    Authentication result = provider.authenticate(request(client));

    assertThat(result).isInstanceOf(OAuth2TokenRevocationAuthenticationToken.class);
    assertThat(result.isAuthenticated()).isTrue();
    assertThat(result.getPrincipal()).isSameAs(client);
    ArgumentCaptor<RevokeRefreshTokenCommand> command =
        ArgumentCaptor.forClass(RevokeRefreshTokenCommand.class);
    verify(revoke).handle(command.capture());
    assertThat(command.getValue().rawToken()).isEqualTo("raw-refresh-token");
    assertThat(command.getValue().organizationId()).isEqualTo(new OrganizationId(ORGANIZATION_ID));
  }

  @Test
  void anythingItDoesNotRevokeIsHandedOnToTheStockProvider() {
    setIssuer("https://clavaris.example.com/o/" + ORGANIZATION_ID);
    when(revoke.handle(any())).thenReturn(false);

    assertThat(provider.authenticate(request(authenticatedClient()))).isNull();
  }

  @Test
  void aClientThatIsNotAuthenticatedIsLeftToTheStockProviderWithoutLookingAtTheToken() {
    setIssuer("https://clavaris.example.com/o/" + ORGANIZATION_ID);
    OAuth2ClientAuthenticationToken unauthenticated = mock(OAuth2ClientAuthenticationToken.class);
    when(unauthenticated.isAuthenticated()).thenReturn(false);

    assertThat(provider.authenticate(request(unauthenticated))).isNull();
    assertThat(provider.authenticate(request(mock(Authentication.class)))).isNull();
    verifyNoInteractions(revoke);
  }

  @Test
  void outsideAnOrganizationIssuerItDoesNothing() {
    setIssuer("https://clavaris.example.com/oauth2/token");

    assertThat(provider.authenticate(request(authenticatedClient()))).isNull();
    verifyNoInteractions(revoke);
  }

  @Test
  void supportsOnlyRevocationRequests() {
    assertThat(provider.supports(OAuth2TokenRevocationAuthenticationToken.class)).isTrue();
    assertThat(provider.supports(OAuth2ClientAuthenticationToken.class)).isFalse();
  }
}
