package com.clavaris.identity.application.usecase.authenticatewithemailcode;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link AuthenticateWithEmailCodeUseCase} — ADR-0024 §3. Every rejection
 * collapses to {@link InvalidOneTimeCodeException}, same anti-enumeration posture {@code
 * AuthenticateWithPasswordService} already establishes.
 *
 * <p>Successfully completing this IS proof of email control — same guarantee clicking a
 * verification link already gives — so this also marks the email verified ({@code
 * Account.verifyEmail()}, idempotent) rather than checking {@code
 * emailVerificationRequiredAtSignIn} the way password login does: an Organization whose only
 * enabled sign-in method is passwordless email must never be able to lock itself out by also
 * requiring verification before sign-in — completing this flow satisfies that requirement by
 * construction, not by bypassing it.
 *
 * <p>Brute-force throttling on the presented code lives at the HTTP layer ({@code RateLimitRule}
 * keyed by the {@code email} form field, same shape {@code login:account} already uses for password
 * login) — not an in-process per-account counter here, same "one mechanism, not two" reasoning that
 * layer already covers this need for.
 */
public class AuthenticateWithEmailCodeService implements AuthenticateWithEmailCodeUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(AuthenticateWithEmailCodeService.class);

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;

  public AuthenticateWithEmailCodeService(
      final AccountRepository accounts, final VerificationTokenRepository tokens) {
    this.accounts = accounts;
    this.tokens = tokens;
  }

  // PMD.CyclomaticComplexity: four genuinely distinct rejection reasons (unknown account,
  // inactive account, invalid/expired/wrong-owner code) collapsed to one exception type, same
  // "wiring, not sprawl" reasoning AuthenticateWithPasswordService's own identical branching
  // already establishes.
  @SuppressWarnings({"PMD.GuardLogStatement", "PMD.CyclomaticComplexity"})
  @Override
  @Transactional
  public AccountId handle(final AuthenticateWithEmailCodeCommand command) {
    final Optional<Account> found =
        accounts.findByOrganizationIdAndEmail(command.organizationId(), command.email());
    if (found.isEmpty()) {
      LOG.info(
          "event=email_code_sign_in_failure organizationId={} reason=unknown_account",
          command.organizationId());
      throw new InvalidOneTimeCodeException();
    }
    final Account account = found.get();

    if (account.status() != AccountStatus.ACTIVE) {
      LOG.info(
          "event=email_code_sign_in_failure organizationId={} accountId={} reason=inactive_account",
          command.organizationId(),
          account.id());
      throw new InvalidOneTimeCodeException();
    }

    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawCode());
    final Optional<VerificationToken> found2 = tokens.findByTokenHash(presentedHash);
    // BR-ORG-02: the resolved token must belong to THIS account (already scoped to
    // command.organizationId() above) — a code hashed to the same value but issued to a different
    // account must never authenticate this one, even though token_hash is already globally unique
    // by construction (defense in depth, same posture ConfirmEmailVerificationService's own type
    // check already establishes for its sibling flow).
    if (found2.isEmpty()
        || found2.get().type() != VerificationTokenType.EMAIL_SIGN_IN_CODE
        || !found2.get().isActive()
        || !found2.get().accountId().equals(account.id())) {
      LOG.info(
          "event=email_code_sign_in_failure organizationId={} accountId={}"
              + " reason=invalid_or_expired_code",
          command.organizationId(),
          account.id());
      throw new InvalidOneTimeCodeException();
    }

    final VerificationToken token = found2.get();
    token.consume();
    tokens.save(token);

    account.verifyEmail();
    accounts.save(account);

    LOG.info(
        "event=email_code_sign_in_success organizationId={} accountId={}",
        command.organizationId(),
        account.id());
    return account.id();
  }
}
