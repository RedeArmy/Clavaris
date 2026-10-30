package com.clavaris.identity.application.usecase.confirmemailverificationcode;

import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.event.AccountEmailVerifiedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.EmailOneTimeCode;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ConfirmEmailVerificationCodeUseCase}. The typed counterpart of {@code
 * ConfirmEmailVerificationService}'s link: the same single-use, same event, but the person types an
 * address as well as the code. That is deliberate. A 30-bit code looked up on its own would let
 * anyone guess a code that belongs to <em>some</em> pending account; asked for the address, a guess
 * has to hit one account's code, and the wrong ones are counted per address (ADR-0033, BR-ID-18).
 */
public class ConfirmEmailVerificationCodeService implements ConfirmEmailVerificationCodeUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(ConfirmEmailVerificationCodeService.class);

  private final VerificationTokenRepository tokens;
  private final AccountRepository accounts;
  private final EventOutboxWriter outbox;
  private final CodeLockout lockout;

  public ConfirmEmailVerificationCodeService(
      final VerificationTokenRepository tokens,
      final AccountRepository accounts,
      final EventOutboxWriter outbox,
      final CodeLockout lockout) {
    this.tokens = tokens;
    this.accounts = accounts;
    this.outbox = outbox;
    this.lockout = lockout;
  }

  @Override
  @Transactional
  public void handle(final ConfirmEmailVerificationCodeCommand command) {
    final String key = CodeLockout.verificationCodeKey(command.organizationId(), command.email());
    // Checked first and for every address alike, so a pause says nothing about which exist.
    lockout.ensureOpen(key);
    try {
      confirm(command);
    } catch (final InvalidVerificationCodeException wrong) {
      lockout.onAddressCodeFailure(command.organizationId(), command.email(), key);
      throw wrong;
    }
    lockout.onSuccess(key);
  }

  private void confirm(final ConfirmEmailVerificationCodeCommand command) {
    final Account account =
        accounts
            .findByOrganizationIdAndEmail(command.organizationId(), command.email())
            .orElseThrow(
                () -> {
                  LOG.info(
                      "event=email_verification_code_failure organizationId={}"
                          + " reason=unknown_account",
                      command.organizationId());
                  return new InvalidVerificationCodeException();
                });

    final VerificationToken token = activeCode(account, command);

    // The atomic, authoritative single-use check; the isActive() test in activeCode is a fast path.
    if (!tokens.consumeIfActive(token.id(), Instant.now())) {
      throw new InvalidVerificationCodeException();
    }

    account.verifyEmail();
    accounts.save(account);
    outbox.write(
        "account.email_verified",
        account.id(),
        account.organizationId(),
        AccountEmailVerifiedEvent.from(account));
    LOG.info(
        "event=email_verification_code_success organizationId={} accountId={}",
        account.organizationId(),
        account.id());
  }

  // The type and owner checks are defence in depth: the hash already mixes the account in.
  private VerificationToken activeCode(
      final Account account, final ConfirmEmailVerificationCodeCommand command) {
    final Optional<VerificationToken> candidate =
        tokens.findByTokenHash(EmailOneTimeCode.hashFor(account.id(), command.presentedRawCode()));
    if (candidate.isEmpty()
        || candidate.get().type() != VerificationTokenType.EMAIL_VERIFICATION
        || !candidate.get().isActive()
        || !candidate.get().accountId().equals(account.id())) {
      LOG.info(
          "event=email_verification_code_failure organizationId={} accountId={}"
              + " reason=invalid_or_expired_code",
          command.organizationId(),
          account.id());
      throw new InvalidVerificationCodeException();
    }
    return candidate.get();
  }
}
