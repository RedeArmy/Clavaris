package com.clavaris.clientregistry.application.usecase.updateoauthclientscopes;

@FunctionalInterface
public interface UpdateOAuthClientScopesUseCase {

  void handle(UpdateOAuthClientScopesCommand command);
}
