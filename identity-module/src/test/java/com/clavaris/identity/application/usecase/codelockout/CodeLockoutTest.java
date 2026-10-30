package com.clavaris.identity.application.usecase.codelockout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodeLockoutTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Email email = new Email("owner@example.com");

  private InMemoryCodeAttemptTracker tracker;
  private AccountRepository accounts;
  private MailSender mailSender;
  private AuditEventRecorder auditEvents;
  private CodeLockout lockout;
  private String key;

  @BeforeEach
  void setUp() {
    tracker = new InMemoryCodeAttemptTracker(3);
    accounts = mock(AccountRepository.class);
    mailSender = mock(MailSender.class);
    auditEvents = mock(AuditEventRecorder.class);
    lockout = new CodeLockout(tracker, accounts, mailSender, auditEvents, Runnable::run);
    key = CodeLockout.signInCodeKey(organizationId, email);
  }

  private void failTimes(final int times) {
    for (int attempt = 0; attempt < times; attempt++) {
      lockout.onSignInCodeFailure(organizationId, email, key);
    }
  }

  @Test
  void anOpenSubjectPassesAndALockedOneIsRefused() {
    lockout.ensureOpen(key);

    failTimes(3);

    assertThatExceptionOfType(CodeTemporarilyLockedException.class)
        .isThrownBy(() -> lockout.ensureOpen(key));
    assertThat(lockout.isLocked(key)).isTrue();
  }

  @Test
  void aRightCodeClearsTheCountSoOldFailuresDoNotAddUp() {
    failTimes(2);

    lockout.onSuccess(key);
    failTimes(2);

    assertThat(lockout.isLocked(key))
        .as("two, then clear, then two is not four in a row")
        .isFalse();
  }

  @Test
  void theFailureThatLocksAuditsAndMailsTheOwnerOfARealAccount() {
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));

    failTimes(3);

    verify(auditEvents)
        .write(
            any(),
            eq("account.code_sign_in_locked"),
            eq("Account"),
            eq(account.id().value().toString()),
            eq("lockedMinutes=15"),
            eq(organizationId.value()));
    verify(mailSender).sendCodeLockedNotice(email.value(), organizationId, Duration.ofMinutes(15));
  }

  @Test
  void aFailureThatDoesNotLockSendsNothing() {
    failTimes(2);

    verifyNoInteractions(mailSender, auditEvents);
  }

  // An address with no account is counted and locked exactly like a real one, so neither tells a
  // stranger anything; only there is nobody to audit or to mail.
  @Test
  void anAddressWithNoAccountLocksTheSameButNothingIsAuditedOrMailed() {
    when(accounts.findByOrganizationIdAndEmail(organizationId, email)).thenReturn(Optional.empty());

    failTimes(3);

    assertThat(lockout.isLocked(key)).isTrue();
    verify(mailSender, never()).sendCodeLockedNotice(any(), any(), any());
    verify(auditEvents, never()).write(any(), any(), any(), any(), any(), any());
  }

  @Test
  void theSecondLockIsLongerAndTheNoticeSaysSo() {
    Account account = Account.register(organizationId, email);
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenReturn(Optional.of(account));
    failTimes(3);
    tracker.endLock(key);

    failTimes(3);

    verify(mailSender).sendCodeLockedNotice(email.value(), organizationId, Duration.ofMinutes(30));
  }

  @Test
  void aFailureWhileAnnouncingNeverReachesTheCaller() {
    when(accounts.findByOrganizationIdAndEmail(organizationId, email))
        .thenThrow(new IllegalStateException("db down"));

    failTimes(3);

    assertThat(lockout.isLocked(key)).as("the lock stands even if the notice failed").isTrue();
  }

  @Test
  void aSaturatedExecutorDropsTheNoticeButKeepsTheLock() {
    CodeLockout saturated =
        new CodeLockout(
            tracker,
            accounts,
            mailSender,
            auditEvents,
            task -> {
              throw new RejectedExecutionException("queue full");
            });

    for (int attempt = 0; attempt < 3; attempt++) {
      saturated.onSignInCodeFailure(organizationId, email, key);
    }

    assertThat(saturated.isLocked(key)).isTrue();
    verifyNoInteractions(mailSender);
  }

  @Test
  void theDeviceCodeIsKeyedByAccountAndAnnouncesTheSameWay() {
    Account account = Account.register(organizationId, email);
    when(accounts.findById(account.id())).thenReturn(Optional.of(account));
    String deviceKey = CodeLockout.deviceCodeKey(account.id());

    for (int attempt = 0; attempt < 3; attempt++) {
      lockout.onDeviceCodeFailure(account.id(), deviceKey);
    }

    assertThat(lockout.isLocked(deviceKey)).isTrue();
    assertThat(lockout.isLocked(key)).as("the sign-in code is a different subject").isFalse();
    verify(mailSender).sendCodeLockedNotice(email.value(), organizationId, Duration.ofMinutes(15));
  }

  @Test
  void theKeysNameTheMethodSoTheTwoNeverShareACount() {
    Account account = Account.register(organizationId, email);

    assertThat(CodeLockout.signInCodeKey(organizationId, email))
        .startsWith("sign-in-code:")
        .isNotEqualTo(CodeLockout.deviceCodeKey(account.id()));
    assertThat(CodeLockout.deviceCodeKey(account.id())).startsWith("device-code:");
  }
}
