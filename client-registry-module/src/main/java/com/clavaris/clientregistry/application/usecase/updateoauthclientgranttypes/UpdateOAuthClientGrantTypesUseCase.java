package com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes;

@FunctionalInterface
public interface UpdateOAuthClientGrantTypesUseCase {

  void handle(UpdateOAuthClientGrantTypesCommand command);
}
