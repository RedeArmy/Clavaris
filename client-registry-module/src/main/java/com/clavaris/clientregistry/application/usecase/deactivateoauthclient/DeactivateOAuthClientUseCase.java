package com.clavaris.clientregistry.application.usecase.deactivateoauthclient;

@FunctionalInterface
public interface DeactivateOAuthClientUseCase {

  void handle(DeactivateOAuthClientCommand command);
}
