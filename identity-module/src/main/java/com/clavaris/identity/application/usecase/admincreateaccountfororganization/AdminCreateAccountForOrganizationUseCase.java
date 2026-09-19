package com.clavaris.identity.application.usecase.admincreateaccountfororganization;

import com.clavaris.identity.domain.model.AccountId;

@FunctionalInterface
public interface AdminCreateAccountForOrganizationUseCase {

  AccountId handle(AdminCreateAccountForOrganizationCommand command);
}
