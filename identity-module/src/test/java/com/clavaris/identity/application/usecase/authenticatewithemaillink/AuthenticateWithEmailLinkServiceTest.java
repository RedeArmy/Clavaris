package com.clavaris.identity.application.usecase.authenticatewithemaillink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthenticateWithEmailLinkServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private AccountRepository accounts;
  private VerificationTokenRepository tokens;
  private AuthenticateWithEmailLinkService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    tokens = mock(VerificationTokenRepository.class);
    service = new AuthenticateWithEmailLinkService(accounts, tokens);
  }

  private VerificationToken issuedToken(final AccountId accountId, final String rawToken) {
    return VerificationToken.issue(
        accountId,
        VerificationTokenType.EMAIL_SIGN_IN_LINK,
        RefreshTokenSecret.hash(rawToken),
        Instant.now().plusSeconds(600));
  }

  @Test
  void consumesAValidLinkVerifiesTheEmailAndReturnsTheAccountId() {
    Account account = Account.register(organizationId, new Email("link-flow@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    String rawToken = "a-real-256-bit-token";
    VerificationToken token = issuedToken(account.id(), rawToken);
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));

    AccountId result =
        service.handle(new AuthenticateWithEmailLinkCommand(organizationId, rawToken));

    assertThat(result).isEqualTo(account.id());
    assertThat(token.consumedAt()).isPresent();
    assertThat(account.emailVerifiedAt()).isPresent();
    verify(tokens).save(token);
    verify(accounts).save(account);
  }

  @Test
  void rejectsAnUnknownToken() {
    when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());

    assertThatExceptionOfType(InvalidSignInLinkException.class)
        .isThrownBy(
            () -> service.handle(new AuthenticateWithEmailLinkCommand(organizationId, "garbage")));

    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsATokenOfTheWrongType() {
    AccountId accountId = AccountId.newId();
    String rawToken = "an-email-verification-token";
    VerificationToken token =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.EMAIL_VERIFICATION,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plusSeconds(600));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));

    assertThatExceptionOfType(InvalidSignInLinkException.class)
        .isThrownBy(
            () -> service.handle(new AuthenticateWithEmailLinkCommand(organizationId, rawToken)));

    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsAnExpiredToken() {
    AccountId accountId = AccountId.newId();
    String rawToken = "an-expired-token";
    VerificationToken token =
        VerificationToken.issue(
            accountId,
            VerificationTokenType.EMAIL_SIGN_IN_LINK,
            RefreshTokenSecret.hash(rawToken),
            Instant.now().minusSeconds(1));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));

    assertThatExceptionOfType(InvalidSignInLinkException.class)
        .isThrownBy(
            () -> service.handle(new AuthenticateWithEmailLinkCommand(organizationId, rawToken)));
  }

  @Test
  void rejectsWhenTheResolvedAccountBelongsToADifferentOrganizationBrOrg02() {
    OrganizationId otherOrganizationId = new OrganizationId(UUID.randomUUID());
    Account account = Account.register(otherOrganizationId, new Email("cross-tenant@example.com"));
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    String rawToken = "a-real-256-bit-token";
    VerificationToken token = issuedToken(account.id(), rawToken);
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));

    assertThatExceptionOfType(InvalidSignInLinkException.class)
        .isThrownBy(
            () -> service.handle(new AuthenticateWithEmailLinkCommand(organizationId, rawToken)));

    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsAnInactiveAccount() {
    Account account = Account.register(organizationId, new Email("suspended@example.com"));
    account.suspend();
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    String rawToken = "a-real-256-bit-token";
    VerificationToken token = issuedToken(account.id(), rawToken);
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawToken))).thenReturn(Optional.of(token));

    assertThatExceptionOfType(InvalidSignInLinkException.class)
        .isThrownBy(
            () -> service.handle(new AuthenticateWithEmailLinkCommand(organizationId, rawToken)));

    verify(accounts, never()).save(any());
  }
}
