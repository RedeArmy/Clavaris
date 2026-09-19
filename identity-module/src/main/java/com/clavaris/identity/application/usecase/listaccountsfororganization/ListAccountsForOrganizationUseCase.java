package com.clavaris.identity.application.usecase.listaccountsfororganization;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.identity.domain.model.Account;

/** Clerk dashboard "Users" tab parity — the paginated list backing that screen. */
@FunctionalInterface
public interface ListAccountsForOrganizationUseCase {

  KeysetPage<Account> handle(ListAccountsForOrganizationQuery query);
}
