package com.clavaris.identity.application.usecase.reactivateaccount;

/** Reverses {@code SuspendAccountUseCase} — no revocation needed, un-blocking is not blocking. */
@FunctionalInterface
public interface ReactivateAccountUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   */
  void handle(ReactivateAccountCommand command);
}
