package com.clavaris.identity.application.usecase.deleteownaccount;

import com.clavaris.identity.application.usecase.deleteaccount.AccountNotFoundException;
import com.clavaris.identity.domain.model.AccountId;

/**
 * Clerk "Allow user to delete their account" parity (ADR-0026) — the self-service "Delete account"
 * action, gated by {@code Account.canDeleteOwnAccount()}. A thin wrapper around {@code
 * DeleteAccountUseCase} (the same real, permanent hard delete the admin-initiated path already
 * uses) — this use case's own entire job is the permission gate, not a second delete
 * implementation.
 */
@FunctionalInterface
public interface DeleteOwnAccountUseCase {

  /**
   * @throws AccountNotFoundException if {@code accountId} doesn't exist
   * @throws SelfDeleteNotAllowedException if {@code Account.canDeleteOwnAccount()} is {@code false}
   */
  void handle(AccountId accountId);
}
