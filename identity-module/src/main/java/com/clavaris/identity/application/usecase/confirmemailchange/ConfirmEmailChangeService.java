package com.clavaris.identity.application.usecase.confirmemailchange;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.requestemailchange.EmailChangeRequestRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.EmailChangeRequest;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-FUT-043: completes a self-service email change from the link mailed to the new address.
 * Possession of that mailbox is the verification, so the Account's address moves and {@code
 * email_verified_at} is set in the same step. Uniqueness is re-checked here — another Account may
 * have taken the address in the hour since the request — with the unique constraint as the backstop
 * for the remaining race.
 *
 * <p>Sessions and tokens are deliberately left alone: the address is an identifier, not a
 * credential, and the change already required the password. The previous address is told it
 * happened, which is the real owner's last chance to notice.
 */
public class ConfirmEmailChangeService implements ConfirmEmailChangeUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ConfirmEmailChangeService.class);

  private final EmailChangeRequestRepository requests;
  private final AccountRepository accounts;
  private final AuditEventRecorder auditEvents;
  private final MailSender mailSender;

  public ConfirmEmailChangeService(
      final EmailChangeRequestRepository requests,
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final MailSender mailSender) {
    this.requests = requests;
    this.accounts = accounts;
    this.auditEvents = auditEvents;
    this.mailSender = mailSender;
  }

  @Override
  @Transactional
  public void handle(final ConfirmEmailChangeCommand command) {
    final EmailChangeRequest request =
        requests
            .findByTokenHash(RefreshTokenSecret.hash(command.rawToken()))
            .orElseThrow(InvalidEmailChangeTokenException::new);
    if (!request.accountId().equals(command.accountId())) {
      throw new InvalidEmailChangeTokenException();
    }
    // The atomic consume is the single-use guard; losing that race looks exactly like a bad link.
    if (!requests.consumeIfActive(request.id(), Instant.now())) {
      throw new InvalidEmailChangeTokenException();
    }
    final Account account =
        accounts.findById(request.accountId()).orElseThrow(InvalidEmailChangeTokenException::new);
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
    auditEvents.write(
        AuditActor.account(account.id().value()),
        "account.email_changed",
        "Account",
        account.id().value().toString(),
        null,
        account.organizationId().value());
    LOG.info("event=email_changed accountId={}", account.id());
    notifyPreviousAddress(account, previousAddress, request.newEmail().value());
  }

  // Failing to reach the old mailbox must not undo a change its owner just proved they control.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void notifyPreviousAddress(
      final Account account, final String previousAddress, final String newAddress) {
    try {
      mailSender.sendEmailChangeNotice(previousAddress, account.organizationId(), newAddress, true);
    } catch (final RuntimeException failure) {
      LOG.warn(
          "event=email_change_notice_failed accountId={} exceptionType={}",
          account.id(),
          failure.getClass().getName());
    }
  }
}
