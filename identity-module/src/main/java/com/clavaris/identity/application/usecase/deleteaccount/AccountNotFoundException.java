package com.clavaris.identity.application.usecase.deleteaccount;

import com.clavaris.identity.domain.model.AccountId;

/**
 * Same "never a dangling reference, never a raw exception past the boundary" rationale as
 * client-registry-module's own {@code OrganizationNotFoundException}.
 */
public final class AccountNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AccountNotFoundException(final AccountId accountId) {
    super("No Account exists with id " + accountId);
  }
}
