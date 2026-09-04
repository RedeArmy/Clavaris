package com.clavaris.identity.application.usecase.impersonateaccount;

/**
 * Validates the target {@code Account} and records that a support/operator impersonation of it
 * began — see {@code app}'s {@code ImpersonationTokenIssuer} for the actual token-minting half of
 * this feature, and {@link ImpersonateAccountCommand}'s own Javadoc for why the two are split
 * across modules.
 */
@FunctionalInterface
public interface ImpersonateAccountUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   * @throws AccountNotActiveException if the Account exists but isn't {@code ACTIVE} — an
   *     impersonation token would otherwise grant access an interactive login of the same account
   *     could never itself obtain (BR-ID: {@code AuthenticateWithPasswordService} already rejects
   *     any non-{@code ACTIVE} account the same way).
   */
  ImpersonateAccountResult handle(ImpersonateAccountCommand command);
}
