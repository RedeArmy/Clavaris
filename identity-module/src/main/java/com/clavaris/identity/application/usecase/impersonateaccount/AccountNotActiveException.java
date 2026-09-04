package com.clavaris.identity.application.usecase.impersonateaccount;

import com.clavaris.identity.domain.model.AccountId;

/**
 * BR-ID: impersonating a {@code SUSPENDED}/{@code DELETED} Account would mint a token granting
 * access an interactive login of that same Account could never itself obtain right now — {@code
 * AuthenticateWithPasswordService} already rejects any non-{@code ACTIVE} account the same way, and
 * this use case must not become a bypass of that rule.
 */
public final class AccountNotActiveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AccountNotActiveException(final AccountId accountId) {
    super("Account " + accountId + " is not ACTIVE — cannot be impersonated");
  }
}
