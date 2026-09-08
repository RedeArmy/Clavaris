package com.clavaris.identity.application.usecase.confirmnewdeviceloginalert;

import com.clavaris.identity.domain.model.AccountId;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * ConfirmNewDeviceLoginAlertService} directly.
 */
@FunctionalInterface
public interface ConfirmNewDeviceLoginAlertUseCase {

  /**
   * @return the {@link AccountId} that was just locked — the caller renders a confirmation page,
   *     never re-establishes a session (the whole point of this action is to end every session).
   * @throws InvalidNewDeviceLoginAlertException if the presented token can't be honored
   */
  AccountId handle(ConfirmNewDeviceLoginAlertCommand command);
}
