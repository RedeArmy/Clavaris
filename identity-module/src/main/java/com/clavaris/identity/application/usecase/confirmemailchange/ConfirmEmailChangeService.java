package com.clavaris.identity.application.usecase.confirmemailchange;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.PendingSocialLinkRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailchange.EmailChangeRequestRepository;
import com.clavaris.identity.application.usecase.requestemailchange.SelfServiceEmailChangePolicy;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.event.AccountEmailChangedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.EmailChangeRequest;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * TD-FUT-043: completes a self-service email change from the link mailed to the new address.
 * Possession of that mailbox is the verification, so the Account's address moves and {@code
 * email_verified_at} is set in the same step. Uniqueness is re-checked here — another Account may
 * have taken the address in the hour since the request — with the unique constraint as the backstop
 * for the remaining race.
 *
 * <p>Everything that touches the database happens in one transaction; the notice mail to the old
 * address is sent after it commits, so no connection is held while waiting on the mail provider.
 *
 * <p>Every still-usable token and pending social link of the Account is ended with the change: they
 * were mailed to the old address, and whoever still reads that mailbox must not be able to finish a
 * password reset or attach a provider identity that was started before the change (BR-ID-04).
 * Sessions and OAuth tokens are left alone — the address is an identifier, not a credential, and
 * the change already required the password.
 */
public class ConfirmEmailChangeService implements ConfirmEmailChangeUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ConfirmEmailChangeService.class);

  private final EmailChangeRequestRepository requests;
  private final AccountRepository accounts;
  private final VerificationTokenRepository verificationTokens;
  private final PendingSocialLinkRepository pendingLinks;
  private final EventOutboxWriter outbox;
  private final AuditEventRecorder auditEvents;
  private final MailSender mailSender;
  private final SelfServiceEmailChangePolicy policy;
  private final TransactionOperations transaction;

  @SuppressWarnings("java:S107") // one parameter per collaborating port.
  public ConfirmEmailChangeService(
      final EmailChangeRequestRepository requests,
      final AccountRepository accounts,
      final VerificationTokenRepository verificationTokens,
      final PendingSocialLinkRepository pendingLinks,
      final EventOutboxWriter outbox,
      final AuditEventRecorder auditEvents,
      final MailSender mailSender,
      final SelfServiceEmailChangePolicy policy,
      final TransactionOperations transaction) {
    this.requests = requests;
    this.accounts = accounts;
    this.verificationTokens = verificationTokens;
    this.pendingLinks = pendingLinks;
    this.outbox = outbox;
    this.auditEvents = auditEvents;
    this.mailSender = mailSender;
    this.policy = policy;
    this.transaction = transaction;
  }

  @Override
  public void handle(final ConfirmEmailChangeCommand command) {
    final Changed changed = transaction.execute(status -> change(command));
    notifyPreviousAddress(changed);
  }

  private Changed change(final ConfirmEmailChangeCommand command) {
    final EmailChangeRequest request = consumeRequestFor(command);
    final Account account =
        accounts.findById(request.accountId()).orElseThrow(InvalidEmailChangeTokenException::new);
    // A link requested before the Organization switched the feature off must not complete after.
    if (!policy.isAllowed(account.organizationId())) {
      throw new InvalidEmailChangeTokenException();
    }
    if (accounts.existsByOrganizationIdAndEmail(account.organizationId(), request.newEmail())) {
      throw new EmailAlreadyRegisteredException(account.organizationId());
    }

    final String previousAddress = account.email().value();
    account.changeEmail(request.newEmail());
    try {
      accounts.save(account);
    } catch (final DataIntegrityViolationException raced) {
      throw new EmailAlreadyRegisteredException(account.organizationId(), raced);
    }
    final Instant now = Instant.now();
    verificationTokens.invalidateAllActiveForAccount(account.id(), now);
    pendingLinks.invalidateAllActiveForAccount(account.id(), now);
    outbox.write(
        "account.email_changed",
        account.id(),
        account.organizationId(),
        AccountEmailChangedEvent.from(account, previousAddress));
    auditEvents.write(
        AuditActor.account(account.id().value()),
        "account.email_changed",
        "Account",
        account.id().value().toString(),
        null,
        account.organizationId().value());
    LOG.info("event=email_changed accountId={}", account.id());
    return new Changed(account, previousAddress);
  }

  // Unknown, someone else's, expired or already used all look the same: an invalid link. The atomic
  // consume is the single-use guard; losing that race looks exactly like a bad link.
  private EmailChangeRequest consumeRequestFor(final ConfirmEmailChangeCommand command) {
    final EmailChangeRequest request =
        requests
            .findByTokenHash(RefreshTokenSecret.hash(command.rawToken()))
            .orElseThrow(InvalidEmailChangeTokenException::new);
    if (!request.accountId().equals(command.accountId())
        || !requests.consumeIfActive(request.id(), Instant.now())) {
      throw new InvalidEmailChangeTokenException();
    }
    return request;
  }

  // Failing to reach the old mailbox must not undo a change its owner just proved they control.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void notifyPreviousAddress(final Changed changed) {
    try {
      mailSender.sendEmailChangeNotice(
          changed.previousAddress(),
          changed.account().organizationId(),
          changed.account().email().value(),
          true);
    } catch (final RuntimeException failure) {
      LOG.warn(
          "event=email_change_notice_failed accountId={} exceptionType={}",
          changed.account().id(),
          failure.getClass().getName());
    }
  }

  private record Changed(Account account, String previousAddress) {}
}
