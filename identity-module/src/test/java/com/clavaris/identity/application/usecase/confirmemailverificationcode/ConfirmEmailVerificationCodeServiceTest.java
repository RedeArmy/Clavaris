package com.clavaris.identity.application.usecase.confirmemailverificationcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException;
import com.clavaris.identity.application.usecase.codelockout.InMemoryCodeAttemptTracker;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.EmailOneTimeCode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmEmailVerificationCodeServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Email email = new Email("verify-by-code@example.com");

  private VerificationTokenRepository tokens;
  private AccountRepository accounts;
  private EventOutboxWriter outbox;
  private CodeLockout lockout;
  private ConfirmEmailVerificationCodeService service;

  @BeforeEach
  void setUp() {
    tokens = mock(VerificationTokenRepository.class);
    accounts = mock(AccountRepository.class);
    outbox = mock(EventOutboxWriter.class);
    lockout =
        new CodeLockout(
            new InMemoryCodeAttemptTracker(3),
            accounts,
            mock(MailSender.class),
            mock(AuditEventRecorder.class),
            Runnable::run);
    service = new ConfirmEmailVerificationCodeService(tokens, accounts, outbox, lockout);
    when(tokens.consumeIfActive(any(), any())).thenReturn(true);
  }

  private Account pendingAccount() {
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    return account;
  }

  private VerificationToken issuedCode(final AccountId owner, final String rawCode) {
    VerificationToken token =
        VerificationToken.issue(
            owner,
            VerificationTokenType.EMAIL_VERIFICATION,
            EmailOneTimeCode.hashFor(owner, rawCode),
            Instant.now().plusSeconds(600));
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(owner, rawCode)))
        .thenReturn(Optional.of(token));
    return token;
  }

  @Test
  void verifiesTheAddressWithTheRightCodeAndConsumesItOnce() {
    Account account = pendingAccount();
    VerificationToken token = issuedCode(account.id(), "K7M2QX");

    service.handle(new ConfirmEmailVerificationCodeCommand(organizationId, email, "K7M2QX"));

    assertThat(account.emailVerifiedAt()).isPresent();
    verify(tokens).consumeIfActive(eq(token.id()), any(Instant.class));
    verify(accounts).save(account);
    verify(outbox).write(eq("account.email_verified"), eq(account.id()), any(), any());
  }

  // ADR-0034: what people type, not what was mailed.
  @Test
  void acceptsLowerCaseSpacesAndHyphens() {
    Account account = pendingAccount();
    issuedCode(account.id(), "K7M2QX");

    service.handle(new ConfirmEmailVerificationCodeCommand(organizationId, email, " k7m-2qx "));

    assertThat(account.emailVerifiedAt()).isPresent();
  }

  @Test
  void aWrongCodeIsRejectedAndVerifiesNothing() {
    Account account = pendingAccount();
    issuedCode(account.id(), "K7M2QX");
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), "AAAAAA")))
        .thenReturn(Optional.empty());
    ConfirmEmailVerificationCodeCommand command =
        new ConfirmEmailVerificationCodeCommand(organizationId, email, "AAAAAA");

    assertThatExceptionOfType(InvalidVerificationCodeException.class)
        .isThrownBy(() -> service.handle(command));

    assertThat(account.emailVerifiedAt()).isEmpty();
    verify(tokens, never()).consumeIfActive(any(), any());
  }

  // The same answer as a wrong code, so the page never says which addresses have an account.
  @Test
  void anUnknownAddressIsRejectedLikeAWrongCode() {
    when(accounts.findByOrganizationIdAndEmail(organizationId, email)).thenReturn(Optional.empty());
    ConfirmEmailVerificationCodeCommand command =
        new ConfirmEmailVerificationCodeCommand(organizationId, email, "K7M2QX");

    assertThatExceptionOfType(InvalidVerificationCodeException.class)
        .isThrownBy(() -> service.handle(command));
  }

  // A code that belongs to another account never verifies this one, even if it is typed here.
  @Test
  void aCodeIssuedToAnotherAccountDoesNotVerifyThisOne() {
    Account account = pendingAccount();
    issuedCode(AccountId.newId(), "K7M2QX");
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), "K7M2QX")))
        .thenReturn(Optional.empty());
    ConfirmEmailVerificationCodeCommand command =
        new ConfirmEmailVerificationCodeCommand(organizationId, email, "K7M2QX");

    assertThatExceptionOfType(InvalidVerificationCodeException.class)
        .isThrownBy(() -> service.handle(command));

    assertThat(account.emailVerifiedAt()).isEmpty();
  }

  @Test
  void aSignInCodeDoesNotVerifyTheEmail() {
    Account account = pendingAccount();
    VerificationToken wrongType =
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_SIGN_IN_CODE,
            EmailOneTimeCode.hashFor(account.id(), "K7M2QX"),
            Instant.now().plusSeconds(600));
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), "K7M2QX")))
        .thenReturn(Optional.of(wrongType));
    ConfirmEmailVerificationCodeCommand command =
        new ConfirmEmailVerificationCodeCommand(organizationId, email, "K7M2QX");

    assertThatExceptionOfType(InvalidVerificationCodeException.class)
        .isThrownBy(() -> service.handle(command));
  }

  @Test
  void loserOfTheSingleUseRaceIsAnInvalidCode() {
    Account account = pendingAccount();
    issuedCode(account.id(), "K7M2QX");
    when(tokens.consumeIfActive(any(), any())).thenReturn(false);
    ConfirmEmailVerificationCodeCommand command =
        new ConfirmEmailVerificationCodeCommand(organizationId, email, "K7M2QX");

    assertThatExceptionOfType(InvalidVerificationCodeException.class)
        .isThrownBy(() -> service.handle(command));

    assertThat(account.emailVerifiedAt()).isEmpty();
  }

  // ADR-0033: after enough wrong codes in a row even the right one is refused until the pause ends.
  @Test
  void pausesAfterTooManyWrongCodesInARow() {
    Account account = pendingAccount();
    issuedCode(account.id(), "K7M2QX");
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), "AAAAAA")))
        .thenReturn(Optional.empty());
    ConfirmEmailVerificationCodeCommand wrong =
        new ConfirmEmailVerificationCodeCommand(organizationId, email, "AAAAAA");
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatExceptionOfType(InvalidVerificationCodeException.class)
          .isThrownBy(() -> service.handle(wrong));
    }
    ConfirmEmailVerificationCodeCommand right =
        new ConfirmEmailVerificationCodeCommand(organizationId, email, "K7M2QX");

    assertThatExceptionOfType(CodeTemporarilyLockedException.class)
        .isThrownBy(() -> service.handle(right));

    assertThat(account.emailVerifiedAt()).isEmpty();
  }
}
