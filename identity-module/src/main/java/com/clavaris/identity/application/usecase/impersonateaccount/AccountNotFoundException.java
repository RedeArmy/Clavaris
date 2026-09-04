package com.clavaris.identity.application.usecase.impersonateaccount;

import com.clavaris.identity.domain.model.AccountId;

/** Same rationale as this module's sibling in {@code suspendaccount}/{@code deleteaccount}. */
public final class AccountNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AccountNotFoundException(final AccountId accountId) {
    super("No Account exists with id " + accountId);
  }
}
