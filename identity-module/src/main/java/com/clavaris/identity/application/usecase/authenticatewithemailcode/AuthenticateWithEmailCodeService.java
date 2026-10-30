package com.clavaris.identity.application.usecase.authenticatewithemailcode;

import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.EmailOneTimeCode;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.time.Instant;
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
  private final CodeLockout lockout;

  public AuthenticateWithEmailCodeService(
      final AccountRepository accounts,
      final VerificationTokenRepository tokens,
      final CodeLockout lockout) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.lockout = lockout;
  }

  // PMD.CyclomaticComplexity: four genuinely distinct rejection reasons (unknown account,
  // inactive account, invalid/expired/wrong-owner code) collapsed to one exception type, same
  // "wiring, not sprawl" reasoning AuthenticateWithPasswordService's own identical branching
  // already establishes.
  @Override
  @Transactional
  public Account handle(final AuthenticateWithEmailCodeCommand command) {
    final String key = CodeLockout.signInCodeKey(command.organizationId(), command.email());
    // ADR-0033: too many wrong codes in a row pause this method for the address. Checked first and
    // for every address alike, so a lock tells nothing about whether an account exists.
    lockout.ensureOpen(key);
    final Account account;
    try {
      account = authenticate(command);
    } catch (final InvalidOneTimeCodeException wrong) {
      lockout.onSignInCodeFailure(command.organizationId(), command.email(), key);
      throw wrong;
    }
    lockout.onSuccess(key);
    return account;
  }

  @SuppressWarnings({"PMD.CyclomaticComplexity"})
  private Account authenticate(final AuthenticateWithEmailCodeCommand command) {
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

    final String presentedHash =
        RefreshTokenSecret.hash(EmailOneTimeCode.normalize(command.presentedRawCode()));
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
    // The atomic, authoritative single-use check: the isActive() test above is only a fast-path
    // rejection. A plain consume() followed by save() let two concurrent confirms with the same
    // code both pass it and both sign in (SP 800-63B-4 §3.1.3.2: a secret is valid only once).
    // The conditional update has exactly one winner; the loser reads false and is an invalid code.
    if (!tokens.consumeIfActive(token.id(), Instant.now())) {
      LOG.info(
          "event=email_code_sign_in_failure organizationId={} accountId={}"
              + " reason=invalid_or_expired_code",
          command.organizationId(),
          account.id());
      throw new InvalidOneTimeCodeException();
    }

    account.verifyEmail();
    // SDE-III review, 2026-09-30 — real bug found and closed: this method never called
    // account.recordSignIn() — same gap AuthenticateWithUsernameService's own identical fix just
    // closed. The dashboard's "Last signed In" column (and, since TD-FUT-034, the activity
    // heatmap's own login_events write) both depend on this having actually happened.
    // TD-ARCH-036: narrow write (sign-in time, and the email verification this factor just proved),
    // never the whole aggregate — see AccountRepository#recordSignIn.
    account.recordSignIn();
    accounts.recordSignInVerifyingEmail(
        account.id(),
        account.lastSignedInAt().orElseThrow(),
        account.emailVerifiedAt().orElseThrow());

    LOG.info(
        "event=email_code_sign_in_success organizationId={} accountId={}",
        command.organizationId(),
        account.id());
    return account;
  }
}
