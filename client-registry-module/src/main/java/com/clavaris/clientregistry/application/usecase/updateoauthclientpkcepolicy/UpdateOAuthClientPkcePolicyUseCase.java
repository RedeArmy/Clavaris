package com.clavaris.clientregistry.application.usecase.updateoauthclientpkcepolicy;

@FunctionalInterface
public interface UpdateOAuthClientPkcePolicyUseCase {

  void handle(UpdateOAuthClientPkcePolicyCommand command);
}
