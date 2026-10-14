package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenCommand;
import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenResult;
import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenUseCase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;

class SessionBackedRefreshTokenGeneratorTest {

  private final IssueRefreshTokenUseCase issue = mock(IssueRefreshTokenUseCase.class);
  private final SessionBackedRefreshTokenGenerator generator =
      new SessionBackedRefreshTokenGenerator(issue);

  // OIDC Core §2: the Session stores when the user authenticated (the instant the first ID token
  // reports as auth_time), so a refreshed ID token can repeat it.
  @Test
  void theSessionIsOpenedWithTheInstantOfTheLatestAuthenticationFactor() {
    Instant earlier = Instant.now().minusSeconds(120);
    Instant latest = Instant.now().minusSeconds(40);
    OAuth2TokenContext context =
        contextWith(
            FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(earlier)
                .build(),
            FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.OTT_AUTHORITY)
                .issuedAt(latest)
                .build());
    when(issue.handle(any()))
        .thenReturn(
            new IssueRefreshTokenResult(UUID.randomUUID(), "raw", Instant.now().plusSeconds(60)));

    generator.generate(context);

    ArgumentCaptor<IssueRefreshTokenCommand> command =
        ArgumentCaptor.forClass(IssueRefreshTokenCommand.class);
    verify(issue).handle(command.capture());
    assertThat(command.getValue().authenticatedAt()).isEqualTo(latest);
  }

  private static OAuth2TokenContext contextWith(
      final FactorGrantedAuthority first, final FactorGrantedAuthority second) {
    RegisteredClient client = mock(RegisteredClient.class);
    when(client.getTokenSettings())
        .thenReturn(TokenSettings.builder().refreshTokenTimeToLive(Duration.ofDays(1)).build());
    OAuth2TokenContext context = mock(OAuth2TokenContext.class);
    when(context.getTokenType()).thenReturn(OAuth2TokenType.REFRESH_TOKEN);
    when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.AUTHORIZATION_CODE);
    when(context.getRegisteredClient()).thenReturn(client);
    when(context.getAuthorizedScopes()).thenReturn(Set.of("openid"));
    when(context.getPrincipal())
        .thenReturn(
            UsernamePasswordAuthenticationToken.authenticated(
                UUID.randomUUID().toString(), null, List.of(first, second)));
    return context;
  }
}
