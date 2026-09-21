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
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

/**
 * Same discipline as {@link WorkspaceRoleClaimsCustomizerTest} — proves what a real token carries.
 */
class ProfilePictureClaimsCustomizerTest {

  private static final String BASE_URL = "https://clavaris.example.com";

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final ProfilePictureClaimsCustomizer customizer =
      new ProfilePictureClaimsCustomizer(accounts, BASE_URL);

  private static JwtEncodingContext contextFor(
      final UUID accountId, final AuthorizationGrantType grantType, final Set<String> scopes) {
    Authentication principal =
        UsernamePasswordAuthenticationToken.authenticated(accountId.toString(), null, List.of());
    JwtEncodingContext context = mock(JwtEncodingContext.class);
    when(context.getAuthorizationGrantType()).thenReturn(grantType);
    when(context.getPrincipal()).thenReturn(principal);
    when(context.getAuthorizedScopes()).thenReturn(scopes);
    return context;
  }

  private Account accountWith(final UUID accountId, final String firstName, final String lastName) {
    return Account.reconstitute(
        new AccountId(accountId),
        new OrganizationId(UUID.randomUUID()),
        new Email("ada@example.com"),
        Instant.now(),
        null,
        AccountStatus.ACTIVE,
        null,
        null,
        null,
        firstName,
        lastName,
        null,
        null,
        null);
  }

  @Test
  void addsPictureNameAndGivenFamilyNameForAnAccountWithTheProfileScope() {
    UUID accountId = UUID.randomUUID();
    Account account = accountWith(accountId, "Ada", "Lovelace");
    when(accounts.findById(new AccountId(accountId))).thenReturn(Optional.of(account));
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder();
    JwtEncodingContext context =
        contextFor(
            accountId, AuthorizationGrantType.AUTHORIZATION_CODE, Set.of("openid", "profile"));
    when(context.getClaims()).thenReturn(claims);

    customizer.customize(context);

    JwtClaimsSet built = claims.build();
    assertThat(built.getClaimAsString("picture"))
        .isEqualTo(BASE_URL + "/o/" + account.organizationId().value() + "/avatars/" + accountId);
    assertThat(built.getClaimAsString("given_name")).isEqualTo("Ada");
    assertThat(built.getClaimAsString("family_name")).isEqualTo("Lovelace");
    assertThat(built.getClaimAsString("name")).isEqualTo("Ada Lovelace");
  }

  @Test
  void addsNoClaimAtAllWhenTheProfileScopeWasNotAuthorized() {
    UUID accountId = UUID.randomUUID();
    JwtEncodingContext context =
        contextFor(accountId, AuthorizationGrantType.AUTHORIZATION_CODE, Set.of("openid"));

    customizer.customize(context);

    verifyNoInteractions(accounts);
  }

  @Test
  void addsNoClaimAtAllForAClientCredentialsGrant_noEndUserPrincipal() {
    UUID accountId = UUID.randomUUID();
    JwtEncodingContext context =
        contextFor(accountId, AuthorizationGrantType.CLIENT_CREDENTIALS, Set.of("profile"));

    customizer.customize(context);

    verifyNoInteractions(accounts);
  }

  @Test
  void addsPictureEvenWithNoNameOrUsernameSet_alwaysResolvesToSomething() {
    UUID accountId = UUID.randomUUID();
    Account account =
        Account.reconstitute(
            new AccountId(accountId),
            new OrganizationId(UUID.randomUUID()),
            new Email("zed@example.com"),
            Instant.now(),
            null,
            AccountStatus.ACTIVE,
            null,
            null,
            null);
    when(accounts.findById(new AccountId(accountId))).thenReturn(Optional.of(account));
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder();
    JwtEncodingContext context =
        contextFor(accountId, AuthorizationGrantType.AUTHORIZATION_CODE, Set.of("profile"));
    when(context.getClaims()).thenReturn(claims);

    customizer.customize(context);

    JwtClaimsSet built = claims.build();
    assertThat(built.getClaimAsString("picture")).isNotBlank();
    assertThat(built.getClaimAsString("given_name")).isNull();
    assertThat(built.getClaimAsString("name")).isNull();
  }
}
