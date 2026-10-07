package com.clavaris.identity.application.usecase.getaccountprofile;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import java.util.Optional;

/** Orchestration for {@link GetAccountProfileUseCase}. */
public class GetAccountProfileService implements GetAccountProfileUseCase {

  private final AccountRepository accounts;

  public GetAccountProfileService(final AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public Optional<Account> handle(final GetAccountProfileQuery query) {
    return accounts.findById(query.accountId());
  }
}
