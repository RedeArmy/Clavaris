package com.clavaris.identity.application.usecase.requestemailchange;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.authenticatewithpassword.PasswordVerifier;
import com.clavaris.identity.application.usecase.changepassword.IncorrectCurrentPasswordException;
import com.clavaris.identity.application.usecase.changepassword.NoPasswordToChangeException;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-FUT-043: starts a self-service email change. The current password is required (a stolen
 * session alone must not be enough to take over the recovery channel), the new address receives a
 * single-use link, and the <em>old</em> address is told a change was asked for — it is the one the
 * real owner still reads if this is not them. Nothing on the Account changes until the link is used
 * ({@code ConfirmEmailChangeService}).
 */
public class RequestEmailChangeService implements RequestEmailChangeUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(RequestEmailChangeService.class);
  private static final Duration TOKEN_TTL = Duration.ofHours(1);

  private final AccountRepository accounts;
  private final PasswordVerifier verifier;
  private final EmailChangeRequestRepository requests;
  private final MailSender mailSender;
  private final AuditEventRecorder auditEvents;

  public RequestEmailChangeService(
      final AccountRepository accounts,
      final PasswordVerifier verifier,
      final EmailChangeRequestRepository requests,
      final MailSender mailSender,
      final AuditEventRecorder auditEvents) {
    this.accounts = accounts;
    this.verifier = verifier;
    this.requests = requests;
    this.mailSender = mailSender;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final RequestEmailChangeCommand command) {
    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new IllegalStateException("Signed-in account no longer exists"));
    final PasswordCredential credential =
        account.passwordCredential().orElseThrow(NoPasswordToChangeException::new);
    if (!verifier.matches(command.currentRawPassword(), credential.passwordHash())) {
      LOG.warn("event=email_change_rejected reason=incorrect_current accountId={}", account.id());
      throw new IncorrectCurrentPasswordException();
    }
    if (command.newEmail().equals(account.email())) {
      return;
    }
    // Taken address: same outward result as success, no mail, nothing stored (see the use case).
    if (accounts.existsByOrganizationIdAndEmail(account.organizationId(), command.newEmail())) {
      LOG.info("event=email_change_target_taken accountId={}", account.id());
      return;
    }

    final String rawToken = RefreshTokenSecret.generateRawValue();
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
    // A reserved test address has no mailbox; skipping the send keeps the flow usable in tests
    // exactly as RequestEmailVerificationService does.
    if (!TestEmailAddress.isTestAddress(command.newEmail().value())) {
      mailSender.sendEmailChangeVerification(
          command.newEmail().value(), account.organizationId(), rawToken);
    }
    mailSender.sendEmailChangeNotice(
        account.email().value(), account.organizationId(), command.newEmail().value(), false);
  }
}
