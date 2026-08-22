package com.clavaris.identity.application.usecase.requestemailverification;

import com.clavaris.identity.domain.model.AccountId;

/**
 * The given {@link AccountId} doesn't resolve to an existing account. Should only ever indicate a
 * programming error (this use case is always invoked with an id this system itself just created or
 * already trusts), never a user-facing input — no caller should route raw untrusted input to this
 * command's {@code accountId}.
 */
public final class UnknownAccountException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnknownAccountException(final AccountId accountId) {
    super("No account found for id " + accountId.value());
  }
}
