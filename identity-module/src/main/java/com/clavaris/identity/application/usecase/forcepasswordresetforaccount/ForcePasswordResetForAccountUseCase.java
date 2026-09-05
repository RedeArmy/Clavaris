package com.clavaris.identity.application.usecase.forcepasswordresetforaccount;

/**
 * Clerk "session tasks" parity: marks an {@code Account} so its next login pauses at a forced
 * password-reset challenge ({@code SessionTaskGate}) before completing — a real, currently-missing
 * IdP capability (compromised-credential response, or a routine security-hygiene rotation), unlike
 * Clerk's own {@code reset-password}/{@code setup-mfa}/{@code choose-organization} tasks, which map
 * to states this codebase either doesn't have (no MFA) or can't have (single-Workspace-membership,
 * see {@code AccountAuthenticationPolicy}'s own Javadoc).
 */
@FunctionalInterface
public interface ForcePasswordResetForAccountUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   */
  void handle(ForcePasswordResetForAccountCommand command);
}
