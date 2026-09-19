package com.clavaris.identity.application.usecase.suspendaccount;

import com.clavaris.identity.application.usecase.issuerefreshtoken.RefreshTokenRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountSessionRevoker;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.AccountTokenRevoker;
import com.clavaris.identity.domain.model.AccountId;

/**
 * SDE-III review, 2026-09-19 — SonarCloud duplication finding: this four-step "kill every
 * already-live grant this Account holds" cascade was byte-for-byte duplicated between {@link
 * SuspendAccountService} and {@code banaccount.BanAccountService} once the latter was added (Clerk
 * dashboard "Users" parity — {@code AccountStatus}'s own Javadoc explains why the two states/use
 * cases stay separate use cases rather than one reusing the other's service). Extracted once a
 * second real caller existed, not designed speculatively ahead of one. Same {@code sessions}/{@code
 * refreshTokens}/{@link AccountTokenRevoker}/{@link AccountSessionRevoker} call order {@code
 * ConfirmPasswordResetService}'s own BR-ID-04 response cascade also uses independently — that
 * pre-existing call site is deliberately left as its own copy, out of scope for this pass.
 */
// Literals: the repeated string is "PMD.LongVariable" itself, used on both the fields and the
// constructor's port parameters — same rationale as identity-module's own IdentityUseCaseConfig
// class-level suppression for this exact PMD-annotation-string-as-literal false positive.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class AccountRevocationCascade {

  private final SessionRepository sessions;
  private final RefreshTokenRepository refreshTokens;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name, same precedent as every
  // other caller of this port (DeleteAccountService, RotateRefreshTokenService).
  private final AccountTokenRevoker accountTokenRevoker;

  @SuppressWarnings("PMD.LongVariable") // matches the port's own name, same precedent as
  // accountTokenRevoker above.
  private final AccountSessionRevoker accountSessionRevoker;

  public AccountRevocationCascade(
      final SessionRepository sessions,
      final RefreshTokenRepository refreshTokens,
      @SuppressWarnings("PMD.LongVariable") final AccountTokenRevoker accountTokenRevoker,
      @SuppressWarnings("PMD.LongVariable") final AccountSessionRevoker accountSessionRevoker) {
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.accountTokenRevoker = accountTokenRevoker;
    this.accountSessionRevoker = accountSessionRevoker;
  }

  public void revokeEverythingFor(final AccountId accountId) {
    sessions.revokeAllActiveForAccount(accountId);
    refreshTokens.revokeAllActiveForAccount(accountId);
    accountTokenRevoker.revokeAllTokensFor(accountId);
    accountSessionRevoker.revokeAllSessionsFor(accountId);
  }
}
