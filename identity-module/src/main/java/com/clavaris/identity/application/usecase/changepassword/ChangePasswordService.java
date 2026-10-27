package com.clavaris.identity.application.usecase.changepassword;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.AccountActiveSessionsRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ActiveAccountSession;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordChecker;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordException;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.PasswordCredential;
import com.clavaris.identity.domain.service.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-FUT-043: an End-User changes their own password without the forgot-password round trip.
 *
 * <p>Same defences as {@code ConfirmPasswordResetService}, which this is the signed-in sibling of:
 * the new password is checked against the policy and the breach list before anything is changed
 * (BR-ID-07), and a successful change assumes the old password may have been known to someone else
 * (BR-ID-04), so it ends every refresh token, every OAuth token and every other browser session.
 * What differs is that the person is present: the session making the request is kept (they should
 * not be signed out of the page they are using to secure the Account), and the current password is
 * the proof of who is asking, checked through the same {@link PasswordVerifier} and its Argon2
 * concurrency gate as a sign-in.
 *
 * <p>A confirmation is sent to the address of record, so a change made through a hijacked session
 * is visible to the real owner. A failure to send it never undoes the change.
 */
public class ChangePasswordService implements ChangePasswordUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ChangePasswordService.class);

  private final AccountRepository accounts;
  private final PasswordVerifier verifier;
  private final PasswordHasher hasher;
  private final BreachedPasswordChecker breachedPasswordChecker;
  private final SessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;
  private final AccountTokenRevoker accountTokenRevoker;
  private final AccountActiveSessionsRepository activeSessions;
  private final AuditEventRecorder auditEvents;
  private final MailSender mailSender;

  @SuppressWarnings({
    "java:S107",
    "PMD.ExcessiveParameterList"
  }) // one parameter per collaborating port, as ConfirmPasswordReset's.
  public ChangePasswordService(
      final AccountRepository accounts,
      final PasswordVerifier verifier,
      final PasswordHasher hasher,
      final BreachedPasswordChecker breachedPasswordChecker,
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      final AccountTokenRevoker accountTokenRevoker,
      final AccountActiveSessionsRepository activeSessions,
      final AuditEventRecorder auditEvents,
      final MailSender mailSender) {
    this.accounts = accounts;
    this.verifier = verifier;
    this.hasher = hasher;
    this.breachedPasswordChecker = breachedPasswordChecker;
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.accountTokenRevoker = accountTokenRevoker;
    this.activeSessions = activeSessions;
    this.auditEvents = auditEvents;
    this.mailSender = mailSender;
  }

  @Override
  @Transactional
  public void handle(final ChangePasswordCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new IllegalStateException("Signed-in account no longer exists"));
    final PasswordCredential credential =
        account.passwordCredential().orElseThrow(NoPasswordToChangeException::new);

    // The proof of who is asking. Checked first, so a wrong current password reveals nothing about
    // whether the new one would have been acceptable.
    if (!verifier.matches(command.currentRawPassword(), credential.passwordHash())) {
      LOG.warn(
          "event=password_change_rejected reason=incorrect_current accountId={}", account.id());
      throw new IncorrectCurrentPasswordException();
    }
    // Validated before any mutation, as ConfirmPasswordResetService does.
    if (!PasswordPolicy.isSatisfiedBy(command.newRawPassword())) {
      throw new WeakPasswordException();
    }
    if (breachedPasswordChecker.isBreached(command.newRawPassword())) {
      throw new BreachedPasswordException();
    }

    account.resetPasswordCredential(hasher.hash(command.newRawPassword()));
    accounts.save(account);
    accounts.saveCredential(account);

    // BR-ID-04's cascade, minus the session this request is made from.
    sessions.revokeAllActiveForAccount(account.id());
    refreshTokens.revokeAllActiveForAccount(account.id());
    accountTokenRevoker.revokeAllTokensFor(account.id());
    for (final ActiveAccountSession other : activeSessions.findAllByAccountId(account.id())) {
      if (!other.sessionId().equals(command.keepSessionId())) {
        activeSessions.revoke(other.sessionId());
      }
    }

    auditEvents.write(
        command.actor(),
        "account.password_changed",
        "Account",
        account.id().value().toString(),
        null,
        account.organizationId().value());
    LOG.info("event=password_changed accountId={}", account.id());
    notifyOwner(account);
  }

  // The owner finding out is the point of the notice; not being able to tell them must not undo a
  // change the person just made.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void notifyOwner(final Account account) {
    try {
      mailSender.sendPasswordChangedNotice(account.email().value(), account.organizationId());
    } catch (final RuntimeException failure) {
      LOG.warn(
          "event=password_changed_notice_failed accountId={} exceptionType={}",
          account.id(),
          failure.getClass().getName());
    }
  }
}
