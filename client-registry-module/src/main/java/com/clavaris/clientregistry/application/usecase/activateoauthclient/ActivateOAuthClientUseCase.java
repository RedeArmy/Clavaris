package com.clavaris.clientregistry.application.usecase.activateoauthclient;

@FunctionalInterface
public interface ActivateOAuthClientUseCase {

  void handle(ActivateOAuthClientCommand command);
}
