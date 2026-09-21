package com.clavaris.identity.application.usecase.deleteownaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.deleteaccount.AccountNotFoundException;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountCommand;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;

/** Orchestration for {@link DeleteOwnAccountUseCase}. */
public class DeleteOwnAccountService implements DeleteOwnAccountUseCase {

  private final AccountRepository accounts;
  private final DeleteAccountUseCase deleteAccount;

  public DeleteOwnAccountService(
      final AccountRepository accounts, final DeleteAccountUseCase deleteAccount) {
    this.accounts = accounts;
    this.deleteAccount = deleteAccount;
  }

  @Override
  public void handle(final AccountId accountId) {
    final Account account =
        accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));

    if (!account.canDeleteOwnAccount()) {
      throw new SelfDeleteNotAllowedException(accountId);
    }

    deleteAccount.handle(
        new DeleteAccountCommand(accountId, AuditActor.account(accountId.value())));
  }
}
