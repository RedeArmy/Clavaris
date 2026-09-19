package com.clavaris.identity.application.usecase.listaccountsfororganization;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;

public class ListAccountsForOrganizationService implements ListAccountsForOrganizationUseCase {

  private final AccountRepository accounts;

  public ListAccountsForOrganizationService(final AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public KeysetPage<Account> handle(final ListAccountsForOrganizationQuery query) {
    return accounts.findKeysetPageByOrganizationId(query.organizationId(), query.pageRequest());
  }
}
