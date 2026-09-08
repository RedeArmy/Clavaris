package com.clavaris.identity.application.usecase.confirmnewdeviceloginalert;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountCommand;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ConfirmNewDeviceLoginAlertUseCase} — TD-FUT-025: the "this wasn't me"
 * half of the new-device login notification (BR-ID-14), previously informational-only.
 *
 * <p>Deliberately does not reimplement the lock-and-revoke cascade — delegates straight to {@link
 * SuspendAccountUseCase}, the exact same reversible-ban capability {@code SuspendAccountController}
 * (admin API) already uses, which itself already reuses {@code AccountTokenRevoker}/{@code
 * AccountSessionRevoker}/session and refresh-token revocation (see that class's own Javadoc for the
 * full cascade history). Same "100% reuse of an already-built, already-tested capability" precedent
 * {@code WorkspaceMemberRefreshTokenRevoker}/{@code AccountProvisioner#deprovision} already
 * establish elsewhere in this codebase — not a second, drifting copy of BR-ID-08's own
 * suspend-and-revoke invariant.
 *
 * <p>{@link SuspendAccountCommand}'s own Javadoc previously said its actor is "always {@code
 * AuditActor#platformClient}, same tier as every other {@code /api/v1/admin/**} mutation" — this is
 * the first exception to that, by design: the account holder themselves, acting through a link only
 * they received, is the actor, {@link AuditActor#account} — not a platform-tier admin action at
 * all. That Javadoc is updated alongside this class to no longer claim otherwise.
 */
public class ConfirmNewDeviceLoginAlertService implements ConfirmNewDeviceLoginAlertUseCase {

  private final VerificationTokenRepository tokens;
  private final SuspendAccountUseCase suspendAccount;

  public ConfirmNewDeviceLoginAlertService(
      final VerificationTokenRepository tokens, final SuspendAccountUseCase suspendAccount) {
    this.tokens = tokens;
    this.suspendAccount = suspendAccount;
  }

  @Override
  @Transactional
  public AccountId handle(final ConfirmNewDeviceLoginAlertCommand command) {
    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawToken());
    final VerificationToken token =
        tokens
            .findByTokenHash(presentedHash)
            .filter(candidate -> candidate.type() == VerificationTokenType.NEW_DEVICE_LOGIN_ALERT)
            .orElseThrow(InvalidNewDeviceLoginAlertException::new);

    if (!token.isActive()) {
      throw new InvalidNewDeviceLoginAlertException();
    }

    token.consume();
    tokens.save(token);

    final AccountId accountId = token.accountId();
    suspendAccount.handle(
        new SuspendAccountCommand(accountId, AuditActor.account(accountId.value())));

    return accountId;
  }
}
