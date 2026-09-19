package com.clavaris.identity.application.usecase.banaccount;

/**
 * {@code Account.status} transitions to {@code BANNED}, blocking future logins immediately ({@code
 * AuthenticateWithPasswordService} already rejects any non-{@code ACTIVE} account) and killing any
 * already-live session/token — same immediate-revocation cascade {@code
 * suspendaccount.SuspendAccountUseCase} already applies for its own distinct action.
 */
@FunctionalInterface
public interface BanAccountUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   */
  void handle(BanAccountCommand command);
}
