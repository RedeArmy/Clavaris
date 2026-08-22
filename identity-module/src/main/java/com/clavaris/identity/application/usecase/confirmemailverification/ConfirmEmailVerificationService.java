package com.clavaris.identity.application.usecase.confirmemailverification;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.event.AccountEmailVerifiedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ConfirmEmailVerificationUseCase}. Unlike {@code
 * RequestEmailVerificationService}, this IS {@code @Transactional} end to end — every step here is
 * an internal database write (token consumption, {@code Account.verifyEmail()}, the outbox row), no
 * third-party network call, so there's no reason to split the transaction the way the request side
 * does.
 */
public class ConfirmEmailVerificationService implements ConfirmEmailVerificationUseCase {

  private final VerificationTokenRepository tokens;
  private final AccountRepository accounts;
  private final EventOutboxWriter outbox;

  public ConfirmEmailVerificationService(
      final VerificationTokenRepository tokens,
      final AccountRepository accounts,
      final EventOutboxWriter outbox) {
    this.tokens = tokens;
    this.accounts = accounts;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public void handle(final ConfirmEmailVerificationCommand command) {
    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawToken());
    final VerificationToken token =
        tokens.findByTokenHash(presentedHash).orElseThrow(InvalidVerificationTokenException::new);

    // Defense in depth: type mismatch should be structurally near-impossible (token_hash is
    // globally unique by construction), but a password-reset token must never be usable to
    // confirm an email regardless.
    if (token.type() != VerificationTokenType.EMAIL_VERIFICATION || !token.isActive()) {
      throw new InvalidVerificationTokenException();
    }

    token.consume();
    tokens.save(token);

    final Account account =
        accounts.findById(token.accountId()).orElseThrow(InvalidVerificationTokenException::new);
    account.verifyEmail();
    accounts.save(account);

    outbox.write("account.email_verified", account.id(), AccountEmailVerifiedEvent.from(account));
  }
}
