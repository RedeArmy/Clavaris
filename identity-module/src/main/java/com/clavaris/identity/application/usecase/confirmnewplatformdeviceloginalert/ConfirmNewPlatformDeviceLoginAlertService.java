package com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformVerificationTokenRepository;
import com.clavaris.identity.application.usecase.suspendplatformaccount.SuspendPlatformAccountCommand;
import com.clavaris.identity.application.usecase.suspendplatformaccount.SuspendPlatformAccountUseCase;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.PlatformVerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link ConfirmNewPlatformDeviceLoginAlertUseCase} — TD-FUT-031, the
 * platform-tier mirror of {@code confirmnewdeviceloginalert.ConfirmNewDeviceLoginAlertService}.
 * Same "100% reuse of an already-built, already-tested capability" discipline: delegates straight
 * to {@link SuspendPlatformAccountUseCase} rather than duplicating its lock cascade, exactly as the
 * tenant-tier sibling delegates to {@code SuspendAccountUseCase}.
 */
// LongVariable: suspendPlatformAccount names exactly what it is — same convention
// IdentityUseCaseConfig's own identical suppressions already document throughout this codebase.
@SuppressWarnings("PMD.LongVariable")
public class ConfirmNewPlatformDeviceLoginAlertService
    implements ConfirmNewPlatformDeviceLoginAlertUseCase {

  private final PlatformVerificationTokenRepository tokens;
  private final SuspendPlatformAccountUseCase suspendPlatformAccount;

  public ConfirmNewPlatformDeviceLoginAlertService(
      final PlatformVerificationTokenRepository tokens,
      final SuspendPlatformAccountUseCase suspendPlatformAccount) {
    this.tokens = tokens;
    this.suspendPlatformAccount = suspendPlatformAccount;
  }

  @Override
  @Transactional
  public PlatformAccountId handle(final ConfirmNewPlatformDeviceLoginAlertCommand command) {
    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawToken());
    final PlatformVerificationToken token =
        tokens
            .findByTokenHash(presentedHash)
            .filter(candidate -> candidate.type() == VerificationTokenType.NEW_DEVICE_LOGIN_ALERT)
            .orElseThrow(InvalidNewPlatformDeviceLoginAlertException::new);

    if (!token.isActive()) {
      throw new InvalidNewPlatformDeviceLoginAlertException();
    }

    token.consume();
    tokens.save(token);

    final PlatformAccountId platformAccountId = token.platformAccountId();
    suspendPlatformAccount.handle(
        new SuspendPlatformAccountCommand(
            platformAccountId, AuditActor.platformAccount(platformAccountId.value())));

    return platformAccountId;
  }
}
