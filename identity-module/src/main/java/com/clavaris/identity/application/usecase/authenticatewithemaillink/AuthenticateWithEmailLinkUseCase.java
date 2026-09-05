package com.clavaris.identity.application.usecase.authenticatewithemaillink;

import com.clavaris.identity.domain.model.AccountId;

@FunctionalInterface
public interface AuthenticateWithEmailLinkUseCase {

  AccountId handle(AuthenticateWithEmailLinkCommand command);
}
