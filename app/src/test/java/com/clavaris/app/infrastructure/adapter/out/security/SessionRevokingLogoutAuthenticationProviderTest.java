package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.revokesessionbysid.RevokeSessionBySidCommand;
import com.clavaris.identity.application.usecase.revokesessionbysid.RevokeSessionBySidUseCase;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

/**
 * TD-SEC-100: logout ends the Session's tokens, but only for a request the stock logout provider
 * accepted, and it never changes what that provider answers.
 */
class SessionRevokingLogoutAuthenticationProviderTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private final AuthenticationProvider stock = mock(AuthenticationProvider.class);
  private final RevokeSessionBySidUseCase revoke = mock(RevokeSessionBySidUseCase.class);
  private final SessionRevokingLogoutAuthenticationProvider provider =
      new SessionRevokingLogoutAuthenticationProvider(stock, revoke);
  private final Authentication request = mock(Authentication.class);

  @AfterEach
  void resetContext() {
    AuthorizationServerContextHolder.resetContext();
  }

  private static void setOrganizationIssuer() {
    AuthorizationServerContextHolder.setContext(
        new AuthorizationServerContext() {
          @Override
          public String getIssuer() {
            return "https://clavaris.example.com/o/" + ORGANIZATION_ID;
          }

          @Override
          public AuthorizationServerSettings getAuthorizationServerSettings() {
            return null;
          }
        });
  }

  private static OidcLogoutAuthenticationToken acceptedLogoutWithSid(final String sid) {
    OidcIdToken idToken =
        new OidcIdToken(
            "an-id-token",
            Instant.now().minusSeconds(60),
            Instant.now().plusSeconds(60),
            sid == null ? Map.of("sub", "x") : Map.of("sub", "x", "sid", sid));
    Authentication principal = mock(Authentication.class);
    return new OidcLogoutAuthenticationToken(
        idToken, principal, "browser-session", "client", null, null);
  }

  @Test
  void aLogoutTheStockProviderAcceptedEndsTheSessionNamedBySid() {
    setOrganizationIssuer();
    OidcLogoutAuthenticationToken accepted = acceptedLogoutWithSid("the-sid");
    when(stock.authenticate(request)).thenReturn(accepted);

    Authentication result = provider.authenticate(request);

    assertThat(result).isSameAs(accepted);
    verify(revoke)
        .handle(new RevokeSessionBySidCommand("the-sid", new OrganizationId(ORGANIZATION_ID)));
  }

  @Test
  void aRequestTheStockProviderRefusedRevokesNothingAndKeepsItsAnswer() {
    setOrganizationIssuer();
    when(stock.authenticate(request)).thenThrow(new BadCredentialsException("bad hint"));

    assertThatThrownBy(() -> provider.authenticate(request))
        .isInstanceOf(BadCredentialsException.class);
    verifyNoInteractions(revoke);
  }

  @Test
  void anIdTokenWithoutASidRevokesNothing() {
    setOrganizationIssuer();
    when(stock.authenticate(request)).thenReturn(acceptedLogoutWithSid(null));

    provider.authenticate(request);

    verifyNoInteractions(revoke);
  }

  @Test
  void outsideAnOrganizationIssuerItRevokesNothing() {
    AuthorizationServerContextHolder.setContext(
        new AuthorizationServerContext() {
          @Override
          public String getIssuer() {
            return "https://clavaris.example.com/oauth2/token";
          }

          @Override
          public AuthorizationServerSettings getAuthorizationServerSettings() {
            return null;
          }
        });
    when(stock.authenticate(request)).thenReturn(acceptedLogoutWithSid("the-sid"));

    provider.authenticate(request);

    verifyNoInteractions(revoke);
  }

  @Test
  void aResultThatIsNotALogoutTokenIsReturnedUntouched() {
    setOrganizationIssuer();
    Authentication other = mock(Authentication.class);
    when(stock.authenticate(request)).thenReturn(other);

    assertThat(provider.authenticate(request)).isSameAs(other);
    verify(revoke, never()).handle(any());
  }

  @Test
  void supportsWhatTheStockProviderSupports() {
    when(stock.supports(OidcLogoutAuthenticationToken.class)).thenReturn(true);

    assertThat(provider.supports(OidcLogoutAuthenticationToken.class)).isTrue();
    assertThat(provider.supports(String.class)).isFalse();
  }
}
