package com.clavaris.identity.application.usecase.listpendingaccountsfororganization;

import com.clavaris.identity.domain.model.Account;
import java.util.List;

/** The Organization's approval queue: self-registrations waiting for an operator's decision. */
@FunctionalInterface
public interface ListPendingAccountsForOrganizationUseCase {

  PendingAccounts handle(ListPendingAccountsForOrganizationQuery query);

  /**
   * @param accounts up to the requested number, oldest first
   * @param total how many are waiting in all, which may be more than {@code accounts} holds
   */
  record PendingAccounts(List<Account> accounts, long total) {}
}
