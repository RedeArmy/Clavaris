package com.clavaris.identity.application.usecase.getaccountstatus;

import com.clavaris.identity.domain.model.AccountStatus;
import java.util.Optional;

/**
 * TD-FUT-081 / ADR-0047: reads one Account's status and nothing else. Empty when there is no such
 * Account, which includes one that was erased: a deleted Account has no row (BR-DATA-02), so {@code
 * DELETED} is never an answer.
 */
@FunctionalInterface
public interface GetAccountStatusUseCase {

  Optional<AccountStatus> handle(GetAccountStatusQuery query);
}
