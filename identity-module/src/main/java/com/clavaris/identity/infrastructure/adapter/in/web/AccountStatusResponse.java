package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import java.util.UUID;

/**
 * TD-FUT-081: the whole body of the status read: which Account and what state it is in. No email,
 * no name, nothing else that identifies a person; a consumer that needs more asks for it with the
 * scope that grants it.
 */
public record AccountStatusResponse(UUID accountId, AccountStatus status) {

  public static AccountStatusResponse of(final AccountId accountId, final AccountStatus status) {
    return new AccountStatusResponse(accountId.value(), status);
  }
}
