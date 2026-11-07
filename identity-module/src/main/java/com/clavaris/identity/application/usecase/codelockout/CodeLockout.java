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
import java.util.UUID;
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
 * mailed, and the mail goes out on the recovery pool ({@code recoveryEmailExecutor}), after the
 * failure has been answered: a lock must not make the answer slower or hold a transaction open
 * across a send.
 */
// PMD.TooManyMethods: the key builders (one per subject) and the success/failure hooks (one per
// kind
// of subject) are each a line or two; splitting them would only scatter one small policy.
@SuppressWarnings("PMD.TooManyMethods")
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
    return signInCodeKey(organizationId, email.value());
  }

  /** The same key from what names the subject: an email, or what was typed for an unknown one. */
  public static String signInCodeKey(final OrganizationId organizationId, final String subject) {
    return "sign-in-code:" + organizationId.value() + ":" + subject;
  }

  /** The key of the email verification code for an address of an Organization, account or not. */
  public static String verificationCodeKey(final OrganizationId organizationId, final Email email) {
    return "verification-code:" + organizationId.value() + ":" + email.value();
  }

  /** The key of the device-verification code of an Account. */
  public static String deviceCodeKey(final AccountId accountId) {
    return "device-code:" + accountId.value();
  }

  /** The key of the authenticator-app code entries of an Account (ADR-0040). */
  public static String totpCodeKey(final AccountId accountId) {
    return "totp-code:" + accountId.value();
  }

  /** The key of the entries of one pending authenticator-code challenge (TD-SEC-093). */
  public static String totpChallengeKey(final UUID challengeId) {
    return "totp-challenge:" + challengeId;
  }

  /**
   * The key of the recovery-code entries of an Account, apart from the authenticator's
   * (TD-SEC-092): a six-digit code has to be paused after a few wrong guesses, and whoever knows
   * the password can make those guesses on purpose, so the pause must not also take away the
   * owner's recovery codes, which are 80 bits and are what gets them in meanwhile.
   */
  public static String totpRecoveryKey(final AccountId accountId) {
    return "totp-recovery:" + accountId.value();
  }

  /**
   * Takes an attempt before judging the code, so that guesses are counted even when many arrive
   * together. Checks the pause in the same step, and for every subject alike, so a pause says
   * nothing about whether an account exists.
   *
   * @throws CodeTemporarilyLockedException if the subject is paused or has no attempts left
   */
  public void beginAttempt(final String key) {
    if (!tracker.tryReserveAttempt(key)) {
      throw new CodeTemporarilyLockedException();
    }
  }

  /**
   * Takes one of the {@code limit} entries a pending authenticator-code challenge allows
   * (TD-SEC-093). The challenge is what a sign-in becomes once the password has passed, so its
   * budget is spent by someone who has just proved the password, and when it runs out the sign-in
   * starts again from the password. That bounds how many wrong entries one password check can put
   * on the Account's own, longer pause.
   *
   * @return false when the challenge has no entries left
   */
  public boolean tryChallengeEntry(final UUID challengeId, final int limit) {
    return tracker.tryReserveWithin(totpChallengeKey(challengeId), limit);
  }

  /** The challenge ended, by being passed or abandoned. */
  public void endChallenge(final UUID challengeId) {
    tracker.clear(totpChallengeKey(challengeId));
  }

  public boolean isLocked(final String key) {
    return tracker.isLocked(key);
  }

  /**
   * Ends the pause on the device-verification code of an Account. Called when the owner resets the
   * password: the pause exists because someone who knew the old password was guessing codes, and
   * that password no longer works (ADR-0035).
   */
  public void clearDeviceCode(final AccountId accountId) {
    tracker.clear(deviceCodeKey(accountId));
  }

  /** A right code. */
  public void onSuccess(final String key) {
    tracker.clear(key);
  }

  /**
   * A wrong code for a subject that names no account (an unknown address or username), for an
   * attempt taken with {@link #beginAttempt}: it is paused exactly like a known one, but nobody is
   * mailed.
   */
  public void onReservedUnknownSubjectFailure(final String key) {
    tracker.settleFailedAttempt(key);
  }

  /**
   * A wrong code typed against an address (sign-in, email verification), for an attempt taken with
   * {@link #beginAttempt}.
   */
  public void onReservedAddressCodeFailure(
      final OrganizationId organizationId, final Email email, final String key) {
    final CodeAttemptTracker.Failure failure = tracker.settleFailedAttempt(key);
    if (failure.lockedNow()) {
      announce(
          () -> accounts.findByOrganizationIdAndEmail(organizationId, email), failure.lockedFor());
    }
  }

  /**
   * A wrong code an Account holds, keyed by that Account (the device-verification code, the
   * authenticator-app code or a recovery code), for an attempt taken with {@link #beginAttempt}:
   * already counted, so this only locks and tells the owner when it is the one that reaches the
   * limit.
   */
  public void onReservedAccountCodeFailure(final AccountId accountId, final String key) {
    final CodeAttemptTracker.Failure failure = tracker.settleFailedAttempt(key);
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
              lookup.get().ifPresent(account -> auditAndNotify(account, lockedFor));
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

  private void auditAndNotify(final Account account, final Duration lockedFor) {
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
