package com.clavaris.identity.application.usecase.authenticatewithusername;

import com.clavaris.identity.domain.model.AccountId;

@FunctionalInterface
public interface AuthenticateWithUsernameUseCase {

  AccountId handle(AuthenticateWithUsernameCommand command);
}
