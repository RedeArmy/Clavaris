package com.clavaris.identity.application.usecase.starttotpenrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.confirmtotpenrollment.ConfirmTotpEnrollmentCommand;
import com.clavaris.identity.application.usecase.confirmtotpenrollment.ConfirmTotpEnrollmentService;
import com.clavaris.identity.application.usecase.disabletotp.DisableTotpCommand;
import com.clavaris.identity.application.usecase.disabletotp.DisableTotpService;
import com.clavaris.identity.application.usecase.gettotpstatus.GetTotpStatusService;
import com.clavaris.identity.application.usecase.regeneraterecoverycodes.RegenerateRecoveryCodesCommand;
import com.clavaris.identity.application.usecase.regeneraterecoverycodes.RegenerateRecoveryCodesService;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requiresecondfactor.RequiresSecondFactorService;
import com.clavaris.identity.application.usecase.resettotpforaccount.ResetTotpForAccountCommand;
import com.clavaris.identity.application.usecase.resettotpforaccount.ResetTotpForAccountService;
import com.clavaris.identity.application.usecase.suspendaccount.AccountNotFoundException;
import com.clavaris.identity.application.usecase.verifytotpchallenge.InvalidTotpCodeException;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpCodeVerifier;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpTestSupport;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpTestSupport.CountingTracker;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpTestSupport.FakeCipher;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpTestSupport.InMemoryCredentials;
import com.clavaris.identity.application.usecase.verifytotpchallenge.TotpTestSupport.InMemoryRecoveryCodes;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.TotpCredential;
import com.clavaris.identity.domain.service.Base32;
import com.clavaris.identity.domain.service.TotpAlgorithm;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The whole authenticator-app lifecycle (ADR-0040) against in-memory ports: add, confirm, check,
 * make new recovery codes, remove, operator reset, and the two questions asked of it (is it
 * available, and does a sign-in need it).
 */
class TotpEnrollmentLifecycleTest {

  private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Account account = Account.register(organizationId, new Email("ana@example.com"));
  private final AccountRepository accounts = mock(AccountRepository.class);
  private final InMemoryCredentials credentials = new InMemoryCredentials();
  private final InMemoryRecoveryCodes recoveryCodes = new InMemoryRecoveryCodes();
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);
  private final FakeCipher cipher = new FakeCipher();
  private boolean organizationAllows = true;
  private final AccountAuthenticationPolicyProvider policy =
      org ->
          new AccountAuthenticationPolicySnapshot(
              false,
              com.clavaris.identity.application.usecase.requestemailverification
                  .EmailVerificationMethod.LINK,
              false,
              false,
              false,
              false,
              true,
              false,
              false,
              false,
              organizationAllows);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private StartTotpEnrollmentService start;
  private ConfirmTotpEnrollmentService confirm;
  private TotpCodeVerifier verifier;
  private CodeLockout lockout;

  @BeforeEach
  void setUp() {
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    when(accounts.findOrganizationIdById(account.id())).thenReturn(Optional.of(organizationId));
    lockout = TotpTestSupport.lockout(new CountingTracker(3));
    start =
        new StartTotpEnrollmentService(
            accounts, policy, credentials, cipher, TotpTestSupport.NO_TRANSACTION);
    confirm =
        new ConfirmTotpEnrollmentService(
            credentials, recoveryCodes, cipher, audit, clock, TotpTestSupport.NO_TRANSACTION);
    verifier = new TotpCodeVerifier(credentials, recoveryCodes, cipher, lockout, audit, clock);
  }

  private TotpEnrollmentStart begin() {
    return start.handle(new StartTotpEnrollmentCommand(account.id(), "Acme"));
  }

  private String codeNow(final TotpEnrollmentStart started) {
    return TotpAlgorithm.codeAt(
        Base32.decode(started.secret()), TotpAlgorithm.stepAt(NOW.getEpochSecond()));
  }

  private List<String> enrol() {
    final TotpEnrollmentStart started = begin();
    return confirm.handle(new ConfirmTotpEnrollmentCommand(account.id(), codeNow(started)));
  }

  @Test
  void startingStoresTheSecretEncryptedAndReturnsItOnce() {
    final TotpEnrollmentStart started = begin();

    final TotpCredential stored = credentials.byAccount.get(account.id());
    assertThat(stored.isConfirmed()).isFalse();
    assertThat(stored.encryptedSecret()).startsWith("enc:").doesNotContain(" ");
    assertThat(started.secret()).matches("([A-Z2-7]{4} ?)+");
    assertThat(started.otpauthUri()).startsWith("otpauth://totp/Acme:ana%40example.com?secret=");
    assertThat(started.toString()).doesNotContain(started.secret().replace(" ", ""));
  }

  @Test
  void startingIsRefusedWhenTheOrganizationHasItOff() {
    organizationAllows = false;

    assertThatThrownBy(this::begin).isInstanceOf(TotpNotEnabledException.class);
    assertThat(credentials.byAccount).isEmpty();
  }

  @Test
  void startingAgainReplacesAnAbandonedSetup() {
    final String first = credentials.byAccount.isEmpty() ? begin().secret() : null;
    final String firstStored = credentials.byAccount.get(account.id()).encryptedSecret();
    begin();

    assertThat(first).isNotNull();
    assertThat(credentials.byAccount.get(account.id()).encryptedSecret()).isNotEqualTo(firstStored);
  }

  @Test
  void startingIsRefusedOnceAnAppIsConfirmed() {
    enrol();

    assertThatThrownBy(this::begin).isInstanceOf(TotpAlreadyEnrolledException.class);
  }

  @Test
  void confirmingWithTheRightCodeTurnsItOnAndGivesTenRecoveryCodesOnce() {
    final List<String> codes = enrol();

    assertThat(credentials.existsConfirmed(account.id())).isTrue();
    assertThat(codes).hasSize(10).doesNotHaveDuplicates();
    assertThat(recoveryCodes.countUnused(account.id())).isEqualTo(10);
    verify(audit).write(any(), eq("account.totp_enrolled"), any(), any(), any());
  }

  @Test
  void theConfirmingCodeIsSpentSoItCannotSignSomeoneInAMomentLater() {
    final TotpEnrollmentStart started = begin();
    final String code = codeNow(started);
    confirm.handle(new ConfirmTotpEnrollmentCommand(account.id(), code));

    assertThatThrownBy(() -> verifier.verify(account.id(), code))
        .isInstanceOf(InvalidTotpCodeException.class);
  }

  @Test
  void confirmingWithAWrongCodeLeavesItOffAndMakesNoRecoveryCodes() {
    begin();

    assertThatThrownBy(
            () -> confirm.handle(new ConfirmTotpEnrollmentCommand(account.id(), "000000")))
        .isInstanceOf(InvalidTotpCodeException.class);
    assertThat(credentials.existsConfirmed(account.id())).isFalse();
    assertThat(recoveryCodes.countUnused(account.id())).isZero();
  }

  @Test
  void confirmingWithNothingStartedIsRefused() {
    assertThatThrownBy(
            () -> confirm.handle(new ConfirmTotpEnrollmentCommand(account.id(), "123456")))
        .isInstanceOf(InvalidTotpCodeException.class);
  }

  @Test
  void aRecoveryCodeDoesNotConfirmASetup() {
    begin();

    assertThatThrownBy(
            () ->
                confirm.handle(
                    new ConfirmTotpEnrollmentCommand(account.id(), "ABCD-EFGH-IJKL-MNOP")))
        .isInstanceOf(InvalidTotpCodeException.class);
  }

  @Test
  void signInNeedsTheSecondFactorOnlyWhenTheOrganizationAllowsItAndTheAccountHasOne() {
    final RequiresSecondFactorService requires =
        new RequiresSecondFactorService(accounts, policy, credentials);
    assertThat(requires.handle(account.id())).isFalse();

    begin();
    assertThat(requires.handle(account.id()))
        .as("an unconfirmed setup is never asked for")
        .isFalse();

    enrol();
    assertThat(requires.handle(account.id())).isTrue();

    organizationAllows = false;
    assertThat(requires.handle(account.id()))
        .as("switching the Organization off stops asking without deleting anything")
        .isFalse();
    assertThat(credentials.existsConfirmed(account.id())).isTrue();
  }

  @Test
  void anUnknownAccountNeedsNothing() {
    final RequiresSecondFactorService requires =
        new RequiresSecondFactorService(accounts, policy, credentials);
    when(accounts.findOrganizationIdById(any())).thenReturn(Optional.empty());

    assertThat(requires.handle(com.clavaris.identity.domain.model.AccountId.newId())).isFalse();
  }

  @Test
  void theStatusReportsAvailabilityEnrolmentAndTheCodesLeft() {
    final GetTotpStatusService status =
        new GetTotpStatusService(accounts, policy, credentials, recoveryCodes);
    assertThat(status.handle(account.id()).enrolled()).isFalse();
    assertThat(status.handle(account.id()).availableToOrganization()).isTrue();

    enrol();

    assertThat(status.handle(account.id()).enrolled()).isTrue();
    assertThat(status.handle(account.id()).unusedRecoveryCodes()).isEqualTo(10);
  }

  @Test
  void removingAskForACodeAndThenDeletesEverything() {
    final TotpEnrollmentStart started = begin();
    confirm.handle(new ConfirmTotpEnrollmentCommand(account.id(), codeNow(started)));
    final DisableTotpService disable =
        new DisableTotpService(
            verifier, credentials, recoveryCodes, audit, TotpTestSupport.NO_TRANSACTION);

    assertThatThrownBy(() -> disable.handle(new DisableTotpCommand(account.id(), "000000")))
        .isInstanceOf(InvalidTotpCodeException.class);
    assertThat(credentials.existsConfirmed(account.id())).isTrue();

    final String later =
        TotpAlgorithm.codeAt(
            Base32.decode(started.secret()), TotpAlgorithm.stepAt(NOW.getEpochSecond()) + 1);
    disable.handle(new DisableTotpCommand(account.id(), later));

    assertThat(credentials.existsConfirmed(account.id())).isFalse();
    assertThat(recoveryCodes.countUnused(account.id())).isZero();
    verify(audit).write(any(), eq("account.totp_disabled"), any(), any(), any());
  }

  @Test
  void newRecoveryCodesEndTheOldOnesAndAskForACode() {
    final TotpEnrollmentStart started = begin();
    final List<String> first =
        confirm.handle(new ConfirmTotpEnrollmentCommand(account.id(), codeNow(started)));
    final RegenerateRecoveryCodesService regenerate =
        new RegenerateRecoveryCodesService(
            verifier, credentials, recoveryCodes, audit, TotpTestSupport.NO_TRANSACTION);

    assertThatThrownBy(
            () -> regenerate.handle(new RegenerateRecoveryCodesCommand(account.id(), "000000")))
        .isInstanceOf(InvalidTotpCodeException.class);

    final String later =
        TotpAlgorithm.codeAt(
            Base32.decode(started.secret()), TotpAlgorithm.stepAt(NOW.getEpochSecond()) + 1);
    final List<String> second =
        regenerate.handle(new RegenerateRecoveryCodesCommand(account.id(), later));

    assertThat(second).hasSize(10).doesNotContainAnyElementsOf(first);
    assertThatThrownBy(() -> verifier.verify(account.id(), first.get(0)))
        .isInstanceOf(InvalidTotpCodeException.class);
    assertThat(verifier.verify(account.id(), second.get(0)))
        .isEqualTo(
            com.clavaris.identity.application.usecase.verifytotpchallenge.TotpMethod.RECOVERY_CODE);
  }

  @Test
  void newRecoveryCodesAreRefusedWithoutAConfirmedApp() {
    final RegenerateRecoveryCodesService regenerate =
        new RegenerateRecoveryCodesService(
            verifier, credentials, recoveryCodes, audit, TotpTestSupport.NO_TRANSACTION);

    assertThatThrownBy(
            () -> regenerate.handle(new RegenerateRecoveryCodesCommand(account.id(), "123456")))
        .isInstanceOf(InvalidTotpCodeException.class);
  }

  @Test
  void anOperatorResetRemovesItEndsAnyPauseAndIsAuditedWithTheOperator() {
    enrol();
    final ResetTotpForAccountService reset =
        new ResetTotpForAccountService(
            accounts, credentials, recoveryCodes, lockout, audit, TotpTestSupport.NO_TRANSACTION);
    final AuditActor operator = AuditActor.platformClient("operator-client");

    assertThat(reset.handle(new ResetTotpForAccountCommand(account.id(), operator))).isTrue();

    assertThat(credentials.existsConfirmed(account.id())).isFalse();
    assertThat(recoveryCodes.countUnused(account.id())).isZero();
    verify(audit).write(eq(operator), eq("account.totp_reset"), any(), any(), any());
    assertThat(reset.handle(new ResetTotpForAccountCommand(account.id(), operator)))
        .as("resetting an account that has none is a quiet no-op")
        .isFalse();
  }

  @Test
  void anOperatorResetOfAnUnknownAccountIsReportedAsNotFound() {
    final ResetTotpForAccountService reset =
        new ResetTotpForAccountService(
            accounts, credentials, recoveryCodes, lockout, audit, TotpTestSupport.NO_TRANSACTION);
    final com.clavaris.identity.domain.model.AccountId unknown =
        com.clavaris.identity.domain.model.AccountId.newId();
    when(accounts.findOrganizationIdById(unknown)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                reset.handle(
                    new ResetTotpForAccountCommand(unknown, AuditActor.platformClient("c"))))
        .isInstanceOf(AccountNotFoundException.class);
  }
}
