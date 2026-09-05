package com.clavaris.identity.application.usecase.completeforcedpasswordreset;

import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;

/**
 * Clerk "session tasks" parity: completes the one concrete task this pass implements — see {@code
 * SessionTaskGate}'s own Javadoc for the full precedent and scope.
 */
@FunctionalInterface
public interface CompleteForcedPasswordResetUseCase {

  /**
   * @throws AccountNotFoundException see that exception's own Javadoc
   * @throws WeakPasswordException if the new password fails {@code PasswordPolicy}
   */
  void handle(CompleteForcedPasswordResetCommand command);
}
