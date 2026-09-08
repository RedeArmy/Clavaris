package com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert;

import com.clavaris.identity.domain.model.PlatformAccountId;

/**
 * TD-FUT-031: platform-tier mirror of {@code
 * confirmnewdeviceloginalert.ConfirmNewDeviceLoginAlertUseCase} — the "this wasn't me" half of
 * {@code RecordPlatformAccountLoginDeviceService}'s new-device notification, previously
 * informational-only for {@code PlatformAccount} the same way the tenant tier was before
 * TD-FUT-025.
 */
@FunctionalInterface
public interface ConfirmNewPlatformDeviceLoginAlertUseCase {

  /**
   * @return the {@link PlatformAccountId} that was just suspended
   * @throws InvalidNewPlatformDeviceLoginAlertException if the presented token is unknown, expired,
   *     already consumed, or of a different {@code VerificationTokenType}
   */
  PlatformAccountId handle(ConfirmNewPlatformDeviceLoginAlertCommand command);
}
