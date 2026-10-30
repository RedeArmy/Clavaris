package com.clavaris.identity.application.usecase.codelockout;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the use cases that check a one-time code share about locking (ADR-0033): refuse while the
 * method is paused, count a wrong code, clear on a right one, and when a failure is the one that
 * locks, record it and tell the owner of the address.
 *
 * <p>The sign-in code is keyed by organization and address, not by account, so an address with no
 * account counts and locks exactly like a real one: neither the lock nor the failure that causes it
 * says anything about whether the address exists. The device-verification code is keyed by account,
 * since whoever presents it has already passed the password. Only a real account is audited and
 * mailed, and the mail goes out on {@code emailRequestExecutor}, after the failure has been
 * answered: a lock must not make the answer slower or hold a transaction open across a send.
 */
public class CodeLockout {

  private static final Logger LOG = LoggerFactory.getLogger(CodeLockout.class);

  private final CodeAttemptTracker tracker;
  private final AccountRepository accounts;
  private final MailSender mailSender;
  private final AuditEventRecorder auditEvents;
  private final Executor emailRequestExecutor;

  public CodeLockout(
      final CodeAttemptTracker tracker,
      final AccountRepository accounts,
      final MailSender mailSender,
      final AuditEventRecorder auditEvents,
      final Executor emailRequestExecutor) {
    this.tracker = tracker;
    this.accounts = accounts;
    this.mailSender = mailSender;
    this.auditEvents = auditEvents;
    this.emailRequestExecutor = emailRequestExecutor;
  }

  /** The key of the sign-in code for an address of an Organization, account or not. */
  public static String signInCodeKey(final OrganizationId organizationId, final Email email) {
    return "sign-in-code:" + organizationId.value() + ":" + email.value();
  }

  /** The key of the device-verification code of an Account. */
  public static String deviceCodeKey(final AccountId accountId) {
    return "device-code:" + accountId.value();
  }

  /**
   * @throws CodeTemporarilyLockedException if the method is paused for this key
   */
  public void ensureOpen(final String key) {
    if (tracker.isLocked(key)) {
      throw new CodeTemporarilyLockedException();
    }
  }

  public boolean isLocked(final String key) {
    return tracker.isLocked(key);
  }

  /** A right code. */
  public void onSuccess(final String key) {
    tracker.clear(key);
  }

  /** A wrong code for the sign-in code of an address. */
  public void onSignInCodeFailure(
      final OrganizationId organizationId, final Email email, final String key) {
    final CodeAttemptTracker.Failure failure = tracker.recordFailure(key);
    if (failure.lockedNow()) {
      announce(
          () -> accounts.findByOrganizationIdAndEmail(organizationId, email), failure.lockedFor());
    }
  }

  /** A wrong code for the device-verification code of an Account. */
  public void onDeviceCodeFailure(final AccountId accountId, final String key) {
    final CodeAttemptTracker.Failure failure = tracker.recordFailure(key);
    if (failure.lockedNow()) {
      announce(() -> accounts.findById(accountId), failure.lockedFor());
    }
  }

  // Audits and mails, but only for a real account, and off the request's thread. Nothing that goes
  // wrong here may change what the caller sees.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void announce(
      final java.util.function.Supplier<Optional<Account>> lookup, final Duration lockedFor) {
    try {
      emailRequestExecutor.execute(
          () -> {
            try {
              lookup.get().ifPresent(account -> record(account, lockedFor));
            } catch (final RuntimeException failure) {
              LOG.warn(
                  "event=code_lockout_notice_failed exceptionType={}",
                  failure.getClass().getName());
            }
          });
    } catch (final RejectedExecutionException _) {
      LOG.warn("event=code_lockout_notice_dropped reason=executor_saturated");
    }
  }

  private void record(final Account account, final Duration lockedFor) {
    auditEvents.write(
        AuditActor.account(account.id().value()),
        "account.code_sign_in_locked",
        "Account",
        account.id().value().toString(),
        "lockedMinutes=" + lockedFor.toMinutes(),
        account.organizationId().value());
    mailSender.sendCodeLockedNotice(account.email().value(), account.organizationId(), lockedFor);
    LOG.info(
        "event=code_lockout organizationId={} accountId={} lockedMinutes={}",
        account.organizationId(),
        account.id(),
        lockedFor.toMinutes());
  }
}
