package com.clavaris.identity.application.usecase.getaccountprofile;

import com.clavaris.identity.domain.model.Account;
import java.util.Optional;

/** TD-FUT-041: Backend-API-style profile read for a consuming application's own backend. */
@FunctionalInterface
public interface GetAccountProfileUseCase {

  Optional<Account> handle(GetAccountProfileQuery query);
}
