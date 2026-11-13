package com.clavaris.identity.application.usecase.authenticatewithemailcode;

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
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.EmailOneTimeCode;
import java.time.Clock;
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
  private InMemoryCodeAttemptTracker tracker;
  private AccountAuthenticationPolicyProvider policyProvider;
  private AuthenticateWithEmailCodeService service;

  @BeforeEach
  void setUp() {
    accounts = mock(AccountRepository.class);
    tokens = mock(VerificationTokenRepository.class);
    tracker = new InMemoryCodeAttemptTracker(3);
    policyProvider = mock(AccountAuthenticationPolicyProvider.class);
    // Username sign-in on, as every new Organization has it (ADR-0032).
    when(policyProvider.policyFor(any()))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                false, EmailVerificationMethod.LINK, true, false, false, true, true, false, false));
    service =
        new AuthenticateWithEmailCodeService(
            accounts,
            tokens,
            new CodeLockout(
                tracker,
                accounts,
                mock(MailSender.class),
                mock(AuditEventRecorder.class),
                Runnable::run),
            policyProvider,
            Clock.systemUTC());
  }

  private VerificationToken issuedToken(final AccountId accountId, final String rawCode) {
    return VerificationToken.issue(
        accountId,
        VerificationTokenType.EMAIL_SIGN_IN_CODE,
        EmailOneTimeCode.hashFor(accountId, rawCode),
        Instant.now().plusSeconds(600));
  }

  @Test
  void consumesAValidCodeVerifiesTheEmailAndReturnsTheAccountId() {
    Account account = Account.register(organizationId, email, Instant.now());
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    String rawCode = "482913";
    VerificationToken token = issuedToken(account.id(), rawCode);
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), rawCode)))
        .thenReturn(Optional.of(token));
    when(tokens.consumeIfActive(eq(token.id()), any(Instant.class))).thenReturn(true);

    Account result =
        service.handle(new AuthenticateWithEmailCodeCommand(organizationId, email, rawCode));

    assertThat(result).isEqualTo(account);
    assertThat(account.emailVerifiedAt()).isPresent();
    verify(tokens).consumeIfActive(eq(token.id()), any(Instant.class));
    verify(accounts)
        .recordSignInVerifyingEmail(eq(account.id()), any(Instant.class), any(Instant.class));
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
    Account account = Account.register(organizationId, email, Instant.now());
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
    Account account = Account.register(organizationId, email, Instant.now());
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
    Account account = Account.register(organizationId, email, Instant.now());
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    String rawCode = "111222";
    VerificationToken token = issuedToken(AccountId.newId(), rawCode);
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), rawCode)))
        .thenReturn(Optional.of(token));
    AuthenticateWithEmailCodeCommand command =
        new AuthenticateWithEmailCodeCommand(organizationId, email, rawCode);

    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).save(any());
  }

  @Test
  void rejectsAnExpiredCode() {
    Account account = Account.register(organizationId, email, Instant.now());
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    String rawCode = "333444";
    VerificationToken token =
        VerificationToken.issue(
            account.id(),
            VerificationTokenType.EMAIL_SIGN_IN_CODE,
            EmailOneTimeCode.hashFor(account.id(), rawCode),
            Instant.now().minusSeconds(1));
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), rawCode)))
        .thenReturn(Optional.of(token));
    AuthenticateWithEmailCodeCommand command =
        new AuthenticateWithEmailCodeCommand(organizationId, email, rawCode);

    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(command));
  }

  // The race this fixes: two confirms with the same still-active code both pass the in-memory
  // isActive() check, and only the conditional update has a single winner. The loser reads false
  // and must be treated as an invalid code, never as a sign-in (SP 800-63B-4 §3.1.3.2).
  @Test
  void rejectsACodeAnotherRequestConsumedFirstAndSignsNobodyIn() {
    Account account = Account.register(organizationId, email, Instant.now());
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    String rawCode = "482913";
    VerificationToken token = issuedToken(account.id(), rawCode);
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), rawCode)))
        .thenReturn(Optional.of(token));
    when(tokens.consumeIfActive(eq(token.id()), any(Instant.class))).thenReturn(false);
    AuthenticateWithEmailCodeCommand command =
        new AuthenticateWithEmailCodeCommand(organizationId, email, rawCode);

    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(command));

    assertThat(account.emailVerifiedAt()).isEmpty();
    verify(accounts, never())
        .recordSignInVerifyingEmail(any(), any(Instant.class), any(Instant.class));
  }

  private AuthenticateWithEmailCodeCommand wrongCode() {
    return new AuthenticateWithEmailCodeCommand(organizationId, email, "000000");
  }

  // ADR-0033: three wrong codes in a row pause the method for the address, and the pause refuses
  // even the right code until it ends.
  @Test
  void tooManyWrongCodesInARowPauseTheMethodEvenForTheRightCode() {
    Account account = Account.register(organizationId, email, Instant.now());
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    String rawCode = "482913";
    VerificationToken token = issuedToken(account.id(), rawCode);
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), rawCode)))
        .thenReturn(Optional.of(token));
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), "000000")))
        .thenReturn(Optional.empty());
    when(tokens.consumeIfActive(eq(token.id()), any(Instant.class))).thenReturn(true);
    AuthenticateWithEmailCodeCommand wrong = wrongCode();
    AuthenticateWithEmailCodeCommand right =
        new AuthenticateWithEmailCodeCommand(organizationId, email, rawCode);

    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatExceptionOfType(InvalidOneTimeCodeException.class)
          .isThrownBy(() -> service.handle(wrong));
    }

    assertThatExceptionOfType(CodeTemporarilyLockedException.class)
        .isThrownBy(() -> service.handle(right));
    verify(tokens, never()).consumeIfActive(any(), any(Instant.class));
  }

  // TD-SEC-093: see ConfirmDeviceTrustChallengeServiceTest, same property for this code.
  @Test
  void anEntryIsRefusedUnjudgedWhenTheLimitsWorthAreAlreadyInFlight() {
    String key = CodeLockout.signInCodeKey(organizationId, email);
    for (int inFlight = 0; inFlight < 3; inFlight++) {
      tracker.tryReserveAttempt(key);
    }

    final AuthenticateWithEmailCodeCommand command =
        new AuthenticateWithEmailCodeCommand(organizationId, email, "K7M2QX");

    assertThatExceptionOfType(CodeTemporarilyLockedException.class)
        .isThrownBy(() -> service.handle(command));
    verify(tokens, never()).findByTokenHash(any());
  }

  @Test
  void aRightCodeClearsTheCountSoEarlierFailuresDoNotAddUp() {
    Account account = Account.register(organizationId, email, Instant.now());
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    String rawCode = "482913";
    VerificationToken token = issuedToken(account.id(), rawCode);
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), rawCode)))
        .thenReturn(Optional.of(token));
    when(tokens.consumeIfActive(eq(token.id()), any(Instant.class))).thenReturn(true);
    AuthenticateWithEmailCodeCommand wrong = wrongCode();
    for (int attempt = 0; attempt < 2; attempt++) {
      assertThatExceptionOfType(InvalidOneTimeCodeException.class)
          .isThrownBy(() -> service.handle(wrong));
    }

    service.handle(new AuthenticateWithEmailCodeCommand(organizationId, email, rawCode));

    assertThat(tracker.failuresOf(CodeLockout.signInCodeKey(organizationId, email))).isZero();
  }

  // An address with no account is counted and paused exactly like a real one: the lock must not be
  // a way to tell the two apart.
  @Test
  void anUnknownAddressIsPausedJustLikeARealOne() {
    when(accounts.findByOrganizationIdAndEmail(organizationId, email)).thenReturn(Optional.empty());
    AuthenticateWithEmailCodeCommand wrong = wrongCode();

    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatExceptionOfType(InvalidOneTimeCodeException.class)
          .isThrownBy(() -> service.handle(wrong));
    }

    assertThatExceptionOfType(CodeTemporarilyLockedException.class)
        .isThrownBy(() -> service.handle(wrong));
  }

  // ADR-0034: the code is capitals and digits; a space or a hyphen in the middle, as people add
  // when
  // copying it, reaches the same hash. Case is not folded: the page sends capitals.
  @Test
  void acceptsTheCodeWithSpacesOrHyphensButOnlyInCapitals() {
    Account account = Account.register(organizationId, email, Instant.now());
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    String issued = "K7M2QX";
    VerificationToken token = issuedToken(account.id(), issued);
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), issued)))
        .thenReturn(Optional.of(token));
    when(tokens.consumeIfActive(eq(token.id()), any(Instant.class))).thenReturn(true);

    Account result =
        service.handle(new AuthenticateWithEmailCodeCommand(organizationId, email, " K7M-2QX "));

    assertThat(result).isEqualTo(account);
  }

  @Test
  void aLowerCaseCodeIsRejected() {
    Account account = Account.register(organizationId, email, Instant.now());
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    String issued = "K7M2QX";
    VerificationToken token = issuedToken(account.id(), issued);
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), issued)))
        .thenReturn(Optional.of(token));
    AuthenticateWithEmailCodeCommand lower =
        new AuthenticateWithEmailCodeCommand(organizationId, email, "k7m2qx");

    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(lower));

    verify(tokens, never()).consumeIfActive(any(), any());
  }

  // ADR-0037: the same field takes a username where the Organization allows it.
  @Test
  void signsInWithAUsernameAndTheCodeMailedToItsAccount() {
    Account account = Account.register(organizationId, email, Instant.now());
    Username username = new Username("ana_perez");
    when(accounts.findByOrganizationIdAndUsername(organizationId, username))
        .thenReturn(Optional.of(account));
    String issued = "K7M2QX";
    VerificationToken token = issuedToken(account.id(), issued);
    when(tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), issued)))
        .thenReturn(Optional.of(token));
    when(tokens.consumeIfActive(eq(token.id()), any(Instant.class))).thenReturn(true);

    Account result =
        service.handle(new AuthenticateWithEmailCodeCommand(organizationId, "Ana_Perez", issued));

    assertThat(result).isEqualTo(account);
  }

  // A username where username sign-in is off is "no account", like any unknown value.
  @Test
  void aUsernameIsRejectedWhereUsernameSignInIsOff() {
    when(policyProvider.policyFor(any()))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                false,
                EmailVerificationMethod.LINK,
                true,
                false,
                false,
                false,
                true,
                false,
                false));
    AuthenticateWithEmailCodeCommand command =
        new AuthenticateWithEmailCodeCommand(organizationId, "ana_perez", "K7M2QX");

    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(command));

    verify(accounts, never()).findByOrganizationIdAndUsername(any(), any());
  }

  // One account named two ways shares one counter, so a second identifier does not double the
  // guesses; and an unknown name is paused the same as a known one.
  @Test
  void wrongCodesForOneAccountAreCountedTogetherWhicheverWayItIsNamed() {
    Account account = Account.register(organizationId, email, Instant.now());
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    when(accounts.findByOrganizationIdAndUsername(organizationId, new Username("ana_perez")))
        .thenReturn(Optional.of(account));
    AuthenticateWithEmailCodeCommand byEmail =
        new AuthenticateWithEmailCodeCommand(organizationId, email, "AAAAAA");
    AuthenticateWithEmailCodeCommand byName =
        new AuthenticateWithEmailCodeCommand(organizationId, "ana_perez", "AAAAAA");

    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(byEmail));
    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(byName));
    assertThatExceptionOfType(InvalidOneTimeCodeException.class)
        .isThrownBy(() -> service.handle(byEmail));

    assertThatExceptionOfType(CodeTemporarilyLockedException.class)
        .isThrownBy(() -> service.handle(byName));
  }

  @Test
  void anUnknownUsernameIsPausedAfterTooManyWrongCodesWithoutMailingAnyone() {
    when(accounts.findByOrganizationIdAndUsername(any(), any())).thenReturn(Optional.empty());
    AuthenticateWithEmailCodeCommand ghost =
        new AuthenticateWithEmailCodeCommand(organizationId, "nobody_here", "AAAAAA");
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatExceptionOfType(InvalidOneTimeCodeException.class)
          .isThrownBy(() -> service.handle(ghost));
    }

    assertThatExceptionOfType(CodeTemporarilyLockedException.class)
        .isThrownBy(() -> service.handle(ghost));
  }
}
