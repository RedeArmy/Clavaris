package com.clavaris.identity.application.usecase.revokeaccountsession;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * RevokeAccountSessionService} directly.
 */
@FunctionalInterface
public interface RevokeAccountSessionUseCase {

  /**
   * @throws SessionNotFoundException if {@code command.sessionId()} doesn't resolve to a live
   *     session owned by {@code command.accountId()}
   */
  void handle(RevokeAccountSessionCommand command);
}
