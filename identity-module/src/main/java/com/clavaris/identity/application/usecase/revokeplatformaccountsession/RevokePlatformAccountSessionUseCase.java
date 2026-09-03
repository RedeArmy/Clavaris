package com.clavaris.identity.application.usecase.revokeplatformaccountsession;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * RevokePlatformAccountSessionService} directly.
 */
@FunctionalInterface
public interface RevokePlatformAccountSessionUseCase {

  /**
   * @throws PlatformAccountSessionNotFoundException if {@code command.sessionId()} doesn't resolve
   *     to a live session owned by {@code command.platformAccountId()}
   */
  void handle(RevokePlatformAccountSessionCommand command);
}
