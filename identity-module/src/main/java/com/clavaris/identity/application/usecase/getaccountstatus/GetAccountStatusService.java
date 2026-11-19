package com.clavaris.identity.application.usecase.getaccountstatus;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.AccountStatus;
import java.util.Optional;

/**
 * TD-FUT-081: a status read for a consumer that wants to know whether an Account is still allowed
 * without spending a refresh token (which rotates it, see TD-SEC-103). It loads one column, not the
 * Account with its credentials, and writes nothing, so it can be repeated freely.
 */
public class GetAccountStatusService implements GetAccountStatusUseCase {

  private final AccountRepository accounts;

  public GetAccountStatusService(final AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public Optional<AccountStatus> handle(final GetAccountStatusQuery query) {
    return accounts.findStatusById(query.accountId());
  }
}
