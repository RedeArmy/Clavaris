package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;

class WorkspaceAwareOidcUserInfoMapperTest {

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final WorkspaceAwareOidcUserInfoMapper mapper =
      new WorkspaceAwareOidcUserInfoMapper(accounts);

  private static OidcUserInfoAuthenticationContext contextFor(
      final UUID accountId, final Set<String> scopes) {
    final OidcIdToken idToken =
        OidcIdToken.withTokenValue("id-token")
            .subject(accountId.toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    @SuppressWarnings("unchecked")
    final OAuth2Authorization.Token<OidcIdToken> idTokenHolder =
        mock(OAuth2Authorization.Token.class);
    when(idTokenHolder.getToken()).thenReturn(idToken);
    final OAuth2Authorization authorization = mock(OAuth2Authorization.class);
    when(authorization.getToken(OidcIdToken.class)).thenReturn(idTokenHolder);
    when(authorization.getPrincipalName()).thenReturn(accountId.toString());
    final OAuth2AccessToken accessToken = mock(OAuth2AccessToken.class);
    when(accessToken.getScopes()).thenReturn(scopes);
    final OidcUserInfoAuthenticationContext context = mock(OidcUserInfoAuthenticationContext.class);
    when(context.getAuthorization()).thenReturn(authorization);
    when(context.getAccessToken()).thenReturn(accessToken);
    return context;
  }

  private static Account account(final UUID accountId, final Instant verifiedAt) {
    return Account.reconstitute(
        new AccountId(accountId),
        new OrganizationId(UUID.randomUUID()),
        new Email("eder.test@example.com"),
        Instant.now(),
        verifiedAt,
        AccountStatus.ACTIVE,
        null,
        null,
        null);
  }

  @Test
  void theEmailScopeReturnsTheEmailAndItsVerificationFlagFromTheAccount() {
    final UUID accountId = UUID.randomUUID();
    when(accounts.findById(new AccountId(accountId)))
        .thenReturn(Optional.of(account(accountId, Instant.now())));

    final OidcUserInfo userInfo = mapper.apply(contextFor(accountId, Set.of("openid", "email")));

    assertThat(userInfo.getEmail()).isEqualTo("eder.test@example.com");
    assertThat(userInfo.getEmailVerified()).isTrue();
  }

  @Test
  void anUnverifiedEmailIsReportedAsFalseRatherThanOmitted() {
    final UUID accountId = UUID.randomUUID();
    when(accounts.findById(new AccountId(accountId)))
        .thenReturn(Optional.of(account(accountId, null)));

    final OidcUserInfo userInfo = mapper.apply(contextFor(accountId, Set.of("openid", "email")));

    assertThat(userInfo.getEmailVerified()).isFalse();
  }

  @Test
  void withoutTheEmailScopeNoEmailClaimIsReturnedAndTheAccountIsNotRead() {
    final UUID accountId = UUID.randomUUID();

    final OidcUserInfo userInfo = mapper.apply(contextFor(accountId, Set.of("openid", "profile")));

    assertThat(userInfo.getClaims()).doesNotContainKeys("email", "email_verified");
    verifyNoInteractions(accounts);
  }

  @Test
  void anAccountThatNoLongerExistsLeavesTheEmailOut() {
    final UUID accountId = UUID.randomUUID();
    when(accounts.findById(new AccountId(accountId))).thenReturn(Optional.empty());

    final OidcUserInfo userInfo = mapper.apply(contextFor(accountId, Set.of("openid", "email")));

    assertThat(userInfo.getClaims()).doesNotContainKeys("email", "email_verified");
  }
}
