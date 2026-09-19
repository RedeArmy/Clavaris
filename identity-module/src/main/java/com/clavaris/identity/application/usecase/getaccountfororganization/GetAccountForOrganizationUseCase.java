package com.clavaris.identity.application.usecase.getaccountfororganization;

import com.clavaris.identity.domain.model.Account;
import java.util.Optional;

/** SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab "View Profile" menu item. */
@FunctionalInterface
public interface GetAccountForOrganizationUseCase {

  Optional<Account> handle(GetAccountForOrganizationQuery query);
}
