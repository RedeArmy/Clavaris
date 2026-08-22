package com.clavaris.identity.application.usecase.confirmemailverification;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * ConfirmEmailVerificationService} directly.
 */
@FunctionalInterface
public interface ConfirmEmailVerificationUseCase {

  /**
   * @throws InvalidVerificationTokenException if the presented token can't be honored
   */
  void handle(ConfirmEmailVerificationCommand command);
}
