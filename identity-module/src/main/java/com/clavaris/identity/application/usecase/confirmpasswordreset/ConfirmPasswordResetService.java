package com.clavaris.identity.application.usecase.confirmpasswordreset;

import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.EventOutboxWriter;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.event.PasswordResetCompletedEvent;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.PasswordPolicy;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ConfirmPasswordResetUseCase}. BR-ID-04: "assume prior sessions may be
 * compromised, not just change the credential" — a successful reset revokes every active session,
 * refresh token, and SAS-tracked access/ID token for the account, the exact same three-repository
 * cascade {@code rotaterefreshtoken.RotateRefreshTokenService} already uses for BR-ID-03's reuse
 * response (same ports reused directly, not duplicated).
 *
 * <p>TD-FUT-009: logs {@code event=password_reset_completed} on success — the other half of {@code
 * RequestPasswordResetService}'s own logging, same convention, never the token/password.
 *
 * <p>TD-SEC-031 (SDE-III review, 2026-08-26): {@link AccountSessionRevoker} added to the same
 * cascade — BR-ID-04's "assume prior sessions may be compromised" is only actually true once the
 * hosted-login-page's own {@code HttpSession} is revoked too, not just the SAS-managed token and
 * this module's own {@code Session}/{@code RefreshToken} rows.
 */
// Literals: the repeated string is "PMD.LongVariable" itself, used on the constructor's port
// parameters — same rationale as identity-module's own IdentityUseCaseConfig class-level
// suppression for this exact PMD-annotation-string-as-literal false positive.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class ConfirmPasswordResetService implements ConfirmPasswordResetUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ConfirmPasswordResetService.class);

  private final VerificationTokenRepository tokens;
  private final AccountRepository accounts;
  private final SessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name — same precedent as
  // RotateRefreshTokenService's identical field.
  private final AccountTokenRevoker accountTokenRevoker;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name — same precedent as
  // accountTokenRevoker above.
  private final AccountSessionRevoker accountSessionRevoker;

  private final PasswordHasher hasher;
  private final EventOutboxWriter outbox;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — see RefreshToken's own
  // rationale for the same suppression on a rehydration factory; here it's a use case wiring
  // together every port BR-ID-04's cascade genuinely needs, not excess complexity to hide.
  public ConfirmPasswordResetService(
      final VerificationTokenRepository tokens,
      final AccountRepository accounts,
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker,
      final PasswordHasher hasher,
      final EventOutboxWriter outbox) {
    this.tokens = tokens;
    this.accounts = accounts;
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.accountTokenRevoker = accountTokenRevoker;
    this.accountSessionRevoker = accountSessionRevoker;
    this.hasher = hasher;
    this.outbox = outbox;
  }

  // PMD.GuardLogStatement false positive — same rationale as AuthenticateWithPasswordService's
  // own identical suppression.
  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional
  public void handle(final ConfirmPasswordResetCommand command) {
    // Validated before any mutation, same RFC-6749-§6-style ordering discipline as
    // RotateRefreshTokenService's own scope check — a rejected weak password must not consume the
    // presented token anyway.
    if (!PasswordPolicy.isSatisfiedBy(command.newRawPassword())) {
      throw new WeakPasswordException();
    }

    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawToken());
    final VerificationToken token =
        tokens.findByTokenHash(presentedHash).orElseThrow(InvalidVerificationTokenException::new);

    if (token.type() != VerificationTokenType.PASSWORD_RESET || !token.isActive()) {
      throw new InvalidVerificationTokenException();
    }

    token.consume();
    tokens.save(token);

    final Account account =
        accounts.findById(token.accountId()).orElseThrow(InvalidVerificationTokenException::new);
    account.resetPasswordCredential(hasher.hash(command.newRawPassword()));
    accounts.save(account);

    // BR-ID-04's revocation cascade — identical shape to BR-ID-03's reuse response, see this
    // class's own Javadoc.
    sessions.revokeAllActiveForAccount(account.id());
    refreshTokens.revokeAllActiveForAccount(account.id());
    accountTokenRevoker.revokeAllTokensFor(account.id());
    accountSessionRevoker.revokeAllSessionsFor(account.id());

    outbox.write(
        "password_reset.completed",
        account.id(),
        account.organizationId(),
        PasswordResetCompletedEvent.from(account));
    LOG.info("event=password_reset_completed accountId={}", account.id());
  }
}
