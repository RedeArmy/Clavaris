package com.clavaris.identity.application.usecase.completeforcedpasswordreset;

import com.clavaris.identity.domain.model.AccountId;

/**
 * A genuinely defensive guard, not an expected outcome — {@code accountId} here always comes from
 * an already-validated pending session (never user input), so this only fires if the account was
 * deleted in the narrow window between the interrupted login and this completion step.
 */
public final class AccountNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AccountNotFoundException(final AccountId accountId) {
    super("No Account exists with id " + accountId);
  }
}
