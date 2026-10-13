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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

class EmailClaimsCustomizerTest {

  private static final OAuth2TokenType ID_TOKEN = new OAuth2TokenType("id_token");

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final EmailClaimsCustomizer customizer = new EmailClaimsCustomizer(accounts);

  private static JwtEncodingContext contextFor(
      final UUID accountId,
      final OAuth2TokenType tokenType,
      final AuthorizationGrantType grantType,
      final Set<String> scopes) {
    JwtEncodingContext context = mock(JwtEncodingContext.class);
    when(context.getTokenType()).thenReturn(tokenType);
    when(context.getAuthorizationGrantType()).thenReturn(grantType);
    when(context.getAuthorizedScopes()).thenReturn(scopes);
    when(context.getPrincipal())
        .thenReturn(
            UsernamePasswordAuthenticationToken.authenticated(
                accountId.toString(), null, List.of()));
    return context;
  }

  private Account account(final UUID accountId, final Instant verifiedAt) {
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

  private JwtClaimsSet runWith(
      final UUID accountId, final OAuth2TokenType tokenType, final Set<String> scopes) {
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder();
    JwtEncodingContext context =
        contextFor(accountId, tokenType, AuthorizationGrantType.AUTHORIZATION_CODE, scopes);
    when(context.getClaims()).thenReturn(claims);
    customizer.customize(context);
    return claims.claim("sub", "x").build();
  }

  @Test
  void addsEmailAndVerifiedTrueToTheIdTokenForTheEmailScope() {
    UUID accountId = UUID.randomUUID();
    when(accounts.findById(new AccountId(accountId)))
        .thenReturn(Optional.of(account(accountId, Instant.now())));

    JwtClaimsSet built = runWith(accountId, ID_TOKEN, Set.of("openid", "email"));

    assertThat(built.getClaimAsString("email")).isEqualTo("eder.test@example.com");
    assertThat(built.<Boolean>getClaim("email_verified")).isTrue();
  }

  @Test
  void reportsAnUnverifiedEmailAsFalseRatherThanOmittingIt() {
    UUID accountId = UUID.randomUUID();
    when(accounts.findById(new AccountId(accountId)))
        .thenReturn(Optional.of(account(accountId, null)));

    JwtClaimsSet built = runWith(accountId, ID_TOKEN, Set.of("email"));

    assertThat(built.<Boolean>getClaim("email_verified")).isFalse();
  }

  @Test
  void addsNothingWhenTheEmailScopeWasNotAuthorized() {
    UUID accountId = UUID.randomUUID();
    JwtEncodingContext context =
        contextFor(
            accountId, ID_TOKEN, AuthorizationGrantType.AUTHORIZATION_CODE, Set.of("openid"));

    customizer.customize(context);

    verifyNoInteractions(accounts);
  }

  @Test
  void keepsTheAccessTokenFreeOfEmailClaims() {
    UUID accountId = UUID.randomUUID();
    JwtEncodingContext context =
        contextFor(
            accountId,
            OAuth2TokenType.ACCESS_TOKEN,
            AuthorizationGrantType.AUTHORIZATION_CODE,
            Set.of("email"));

    customizer.customize(context);

    verifyNoInteractions(accounts);
  }

  @Test
  void addsNothingForAClientCredentialsGrant() {
    UUID accountId = UUID.randomUUID();
    JwtEncodingContext context =
        contextFor(accountId, ID_TOKEN, AuthorizationGrantType.CLIENT_CREDENTIALS, Set.of("email"));

    customizer.customize(context);

    verifyNoInteractions(accounts);
  }
}
