package com.clavaris.identity.application.usecase.requestemailverification;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * RequestEmailVerificationService} directly.
 */
@FunctionalInterface
public interface RequestEmailVerificationUseCase {

  /**
   * @throws UnknownAccountException if {@code command.accountId()} doesn't resolve
   */
  void handle(RequestEmailVerificationCommand command);
}
