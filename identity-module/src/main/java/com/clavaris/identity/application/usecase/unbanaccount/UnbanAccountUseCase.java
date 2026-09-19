package com.clavaris.identity.application.usecase.unbanaccount;

/**
 * Reverses {@code banaccount.BanAccountUseCase} — no revocation needed, un-blocking is not
 * blocking.
 */
@FunctionalInterface
public interface UnbanAccountUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   */
  void handle(UnbanAccountCommand command);
}
