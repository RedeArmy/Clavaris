package com.clavaris.identity.application.usecase.listpendingaccountsfororganization;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import java.util.List;

public class ListPendingAccountsForOrganizationService
    implements ListPendingAccountsForOrganizationUseCase {

  private final AccountRepository accounts;

  public ListPendingAccountsForOrganizationService(final AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public PendingAccounts handle(final ListPendingAccountsForOrganizationQuery query) {
    final long total = accounts.countPendingApprovalByOrganizationId(query.organizationId());
    if (query.limit() == 0 || total == 0) {
      return new PendingAccounts(List.of(), total);
    }
    return new PendingAccounts(
        accounts.findPendingApprovalByOrganizationId(query.organizationId(), query.limit()), total);
  }
}
