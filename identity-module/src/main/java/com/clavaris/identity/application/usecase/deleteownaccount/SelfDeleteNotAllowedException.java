package com.clavaris.identity.application.usecase.deleteownaccount;

import com.clavaris.identity.domain.model.AccountId;

/**
 * Thrown when {@code Account.canDeleteOwnAccount()} is {@code false} — Clerk "User permissions"
 * parity (ADR-0026): an operator opts a specific Account into self-delete, never on by default.
 */
public final class SelfDeleteNotAllowedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SelfDeleteNotAllowedException(final AccountId accountId) {
    super("Account " + accountId + " is not allowed to delete its own account");
  }
}
