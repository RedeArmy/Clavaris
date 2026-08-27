package com.clavaris.identity.application.usecase.suspendaccount;

/**
 * Reversible ban (distinct from {@code DeleteAccountUseCase}'s permanent hard delete) — {@code
 * Account.status} transitions to {@code SUSPENDED}, blocking future logins immediately ({@code
 * AuthenticateWithPasswordService} already rejects any non-{@code ACTIVE} account) and killing any
 * already-live session/token.
 */
@FunctionalInterface
public interface SuspendAccountUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   */
  void handle(SuspendAccountCommand command);
}
