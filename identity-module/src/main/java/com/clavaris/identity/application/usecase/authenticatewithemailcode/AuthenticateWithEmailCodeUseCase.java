package com.clavaris.identity.application.usecase.authenticatewithemailcode;

import com.clavaris.identity.domain.model.AccountId;

@FunctionalInterface
public interface AuthenticateWithEmailCodeUseCase {

  AccountId handle(AuthenticateWithEmailCodeCommand command);
}
