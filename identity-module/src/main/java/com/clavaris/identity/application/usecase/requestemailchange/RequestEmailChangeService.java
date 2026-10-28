package com.clavaris.identity.application.usecase.requestemailchange;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.changepassword.IncorrectCurrentPasswordException;
import com.clavaris.identity.application.usecase.changepassword.RecentSignInRequiredException;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.EmailChangeRequest;
import com.clavaris.identity.domain.model.PasswordCredential;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import com.clavaris.identity.domain.service.TestEmailAddress;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * TD-FUT-043: starts a self-service email change. The current password is required (a stolen
 * session alone must not be enough to take over the recovery channel), the new address receives a
 * single-use link, and the <em>old</em> address is told a change was asked for — it is the one the
 * real owner still reads if this is not them. Nothing on the Account changes until the link is used
 * ({@code ConfirmEmailChangeService}).
 *
 * <p>An Account with no password proves itself with a sign-in made moments ago instead (see {@code
 * proveWhoIsAsking}).
 *
 * <p>The password check (Argon2, behind its concurrency gate) and both mails happen outside any
 * database transaction; only the lookup-and-store step runs inside one, so a pooled connection is
 * never held while waiting on the gate or on the mail provider.
 */
public class RequestEmailChangeService implements RequestEmailChangeUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RequestEmailChangeService.class);
  private static final Duration TOKEN_TTL = Duration.ofHours(1);

  private final AccountRepository accounts;
  private final PasswordVerifier verifier;
  private final EmailChangeRequestRepository requests;
  private final MailSender mailSender;
  private final AuditEventRecorder auditEvents;
  private final SelfServiceEmailChangePolicy policy;
  private final TransactionOperations transaction;

  public RequestEmailChangeService(
      final AccountRepository accounts,
      final PasswordVerifier verifier,
      final EmailChangeRequestRepository requests,
      final MailSender mailSender,
      final AuditEventRecorder auditEvents,
      final SelfServiceEmailChangePolicy policy,
      final TransactionOperations transaction) {
    this.accounts = accounts;
    this.verifier = verifier;
    this.requests = requests;
    this.mailSender = mailSender;
    this.auditEvents = auditEvents;
    this.policy = policy;
    this.transaction = transaction;
  }

  @Override
  public void handle(final RequestEmailChangeCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new IllegalStateException("Signed-in account no longer exists"));
    // Checked before the proof, so an Organization that switched this off never has its End-Users
    // type a password into a form that cannot succeed.
    if (!policy.isAllowed(account.organizationId())) {
      throw new EmailChangeNotAllowedException();
    }
    proveWhoIsAsking(account, command);
    if (command.newEmail().equals(account.email())) {
      return;
    }

    final String rawToken = RefreshTokenSecret.generateRawValue();
    final boolean stored =
        Boolean.TRUE.equals(transaction.execute(status -> store(account, command, rawToken)));
    if (!stored) {
      return;
    }
    // A reserved test address has no mailbox; skipping the send keeps the flow usable in tests
    // exactly as RequestEmailVerificationService does.
    if (!TestEmailAddress.isTestAddress(command.newEmail().value())) {
      mailSender.sendEmailChangeVerification(
          command.newEmail().value(), account.organizationId(), rawToken);
    }
    mailSender.sendEmailChangeNotice(
        account.email().value(), account.organizationId(), command.newEmail().value(), false);
  }

  // A password if the person chose one. An Account that signs in only with a provider or a passkey
  // (its stored password is the random one generated at sign-up) has nothing to type, so a sign-in
  // made moments ago is the proof instead (TD-FUT-053): a stolen
  // or long-idle session cannot take over the recovery channel, a person who just signed in can.
  private void proveWhoIsAsking(final Account account, final RequestEmailChangeCommand command) {
    if (!account.hasUserChosenPassword()) {
      if (!command.recentlySignedIn()) {
        throw new RecentSignInRequiredException();
      }
      return;
    }
    final PasswordCredential credential = account.passwordCredential().orElseThrow();
    if (!verifier.matches(command.currentRawPassword(), credential.passwordHash())) {
      LOG.warn("event=email_change_rejected reason=incorrect_current accountId={}", account.id());
      throw new IncorrectCurrentPasswordException();
    }
  }

  // Taken address: same outward result as success, no mail, nothing stored (see the use case).
  private boolean store(
      final Account account, final RequestEmailChangeCommand command, final String rawToken) {
    if (accounts.existsByOrganizationIdAndEmail(account.organizationId(), command.newEmail())) {
      LOG.info("event=email_change_target_taken accountId={}", account.id());
      return false;
    }
    requests.replaceFor(
        EmailChangeRequest.issue(
            account.id(),
            command.newEmail(),
            RefreshTokenSecret.hash(rawToken),
            Instant.now().plus(TOKEN_TTL)));
    auditEvents.write(
        command.actor(),
        "account.email_change_requested",
        "Account",
        account.id().value().toString(),
        null,
        account.organizationId().value());
    return true;
  }
}
