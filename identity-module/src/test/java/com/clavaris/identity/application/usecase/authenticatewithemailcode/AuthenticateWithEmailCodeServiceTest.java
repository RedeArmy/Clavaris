package com.clavaris.identity.application.usecase.authenticatewithemailcode;

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

class AuthenticateWithEmailCodeServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Email email = new Email("code-flow@example.com");

  private AccountRepository accounts;
  private VerificationTokenRepository tokens;
  private AuthenticateWithEmailCodeService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    tokens = mock(VerificationTokenRepository.class);
    service = new AuthenticateWithEmailCodeService(accounts, tokens);
  }

  private VerificationToken issuedToken(final AccountId accountId, final String rawCode) {
    return VerificationToken.issue(
        accountId,
        VerificationTokenType.EMAIL_SIGN_IN_CODE,
        RefreshTokenSecret.hash(rawCode),
        Instant.now().plusSeconds(600));
  }

  @Test
  void consumesAValidCodeVerifiesTheEmailAndReturnsTheAccountId() {
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    String rawCode = "482913";
    VerificationToken token = issuedToken(account.id(), rawCode);
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawCode))).thenReturn(Optional.of(token));

    AccountId result =
        service.handle(new AuthenticateWithEmailCodeCommand(organizationId, email, rawCode));

    assertThat(result).isEqualTo(account.id());
    assertThat(token.consumedAt()).isPresent();
    assertThat(account.emailVerifiedAt()).isPresent();
    verify(tokens).save(token);
    verify(accounts).save(account);
  }

  @Test
  void rejectsAnUnknownAccount() {
    when(accounts.findByOrganizationIdAndEmail(organizationId, email)).thenReturn(Optional.empty());
    AuthenticateWithEmailCodeCommand command =
        new AuthenticateWithEmailCodeCommand(organizationId, email, "000000");

    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(command));

    verify(tokens, never()).findByTokenHash(any());
  }

  @Test
  void rejectsAnInactiveAccount() {
    Account account = Account.register(organizationId, email);
    account.suspend();
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    AuthenticateWithEmailCodeCommand command =
        new AuthenticateWithEmailCodeCommand(organizationId, email, "000000");

    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsAnUnknownCode() {
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());
    AuthenticateWithEmailCodeCommand command =
        new AuthenticateWithEmailCodeCommand(organizationId, email, "000000");

    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsACodeIssuedToADifferentAccountBrOrg02() {
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    String rawCode = "111222";
    VerificationToken token = issuedToken(AccountId.newId(), rawCode);
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawCode))).thenReturn(Optional.of(token));
    AuthenticateWithEmailCodeCommand command =
        new AuthenticateWithEmailCodeCommand(organizationId, email, rawCode);

    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsAnExpiredCode() {
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    String rawCode = "333444";
    VerificationToken token =
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_SIGN_IN_CODE,
            RefreshTokenSecret.hash(rawCode),
            Instant.now().minusSeconds(1));
    when(tokens.findByTokenHash(RefreshTokenSecret.hash(rawCode))).thenReturn(Optional.of(token));
    AuthenticateWithEmailCodeCommand command =
        new AuthenticateWithEmailCodeCommand(organizationId, email, rawCode);

    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(command));
  }
}
