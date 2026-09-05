package com.clavaris.identity.application.usecase.completeforcedpasswordreset;

import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registeraccount.PasswordHasher;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.service.PasswordPolicy;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link CompleteForcedPasswordResetUseCase}. Same BR-ID-04-shaped revocation
 * cascade as {@code ConfirmPasswordResetService} — an operator forcing this is often itself a
 * compromise response, so every prior session/token is treated as potentially compromised here too,
 * not just for the self-service reset flow. Unlike that service, there is no {@code
 * VerificationToken} here at all: {@code accountId} comes from an already-validated pending session
 * ({@code SessionTaskGate}/{@code SessionTaskChallengeController}), not a presented token.
 *
 * <p>Attaches a first credential ({@link Account#attachPasswordCredential}) rather than replacing
 * one ({@link Account#resetPasswordCredential}) when the account has none yet — covers the
 * password-optional-account edge case {@code Account}'s own Javadoc documents (ADR-0024 §5, a
 * forced reset on an account that signed up passwordless).
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals") // "PMD.LongVariable" itself, on the constructor's
// port parameters — same rationale as ConfirmPasswordResetService's own identical suppression.
public class CompleteForcedPasswordResetService implements CompleteForcedPasswordResetUseCase {

  private final AccountRepository accounts;
  private final SessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;

  @SuppressWarnings("PMD.LongVariable")
  private final AccountTokenRevoker accountTokenRevoker;

  @SuppressWarnings("PMD.LongVariable")
  private final AccountSessionRevoker accountSessionRevoker;

  private final PasswordHasher hasher;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // ConfirmPasswordResetService's own identical suppression for the same BR-ID-04-shaped cascade.
  public CompleteForcedPasswordResetService(
      final AccountRepository accounts,
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker,
      final PasswordHasher hasher) {
    this.accounts = accounts;
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.accountTokenRevoker = accountTokenRevoker;
    this.accountSessionRevoker = accountSessionRevoker;
    this.hasher = hasher;
  }

  @Override
  @Transactional
  public void handle(final CompleteForcedPasswordResetCommand command) {
    if (!PasswordPolicy.isSatisfiedBy(command.newRawPassword())) {
      throw new WeakPasswordException();
    }

    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    final String hash = hasher.hash(command.newRawPassword());
    if (account.passwordCredential().isPresent()) {
      account.resetPasswordCredential(hash);
    } else {
      account.attachPasswordCredential(hash);
    }
    accounts.save(account);

    // BR-ID-04's revocation cascade — identical shape to ConfirmPasswordResetService's own,
    // deliberately not the hosted-login HttpSession itself (there is no established session yet
    // at this point in the flow — SessionTaskGate paused before AuthenticatedSessionEstablisher
    // ever ran — so AccountSessionRevoker here only ever reaches prior, already-established ones).
    sessions.revokeAllActiveForAccount(account.id());
    refreshTokens.revokeAllActiveForAccount(account.id());
    accountTokenRevoker.revokeAllTokensFor(account.id());
    accountSessionRevoker.revokeAllSessionsFor(account.id());
  }
}
