package com.clavaris.identity.application.usecase.getaccountfororganization;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import java.util.Optional;

/** Orchestration for {@link GetAccountForOrganizationUseCase}. */
public class GetAccountForOrganizationService implements GetAccountForOrganizationUseCase {

  private final AccountRepository accounts;

  public GetAccountForOrganizationService(final AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public Optional<Account> handle(final GetAccountForOrganizationQuery query) {
    return accounts
        .findById(query.accountId())
        .filter(account -> account.organizationId().equals(query.organizationId()));
  }
}
