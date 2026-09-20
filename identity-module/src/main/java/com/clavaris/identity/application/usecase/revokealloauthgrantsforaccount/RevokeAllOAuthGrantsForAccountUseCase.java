package com.clavaris.identity.application.usecase.revokealloauthgrantsforaccount;

@FunctionalInterface
public interface RevokeAllOAuthGrantsForAccountUseCase {

  void handle(RevokeAllOAuthGrantsForAccountCommand command);
}
