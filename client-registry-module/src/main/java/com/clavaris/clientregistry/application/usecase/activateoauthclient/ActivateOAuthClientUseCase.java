package com.clavaris.clientregistry.application.usecase.activateoauthclient;

@FunctionalInterface
public interface ActivateOAuthClientUseCase {

  ActivateOAuthClientResult handle(ActivateOAuthClientCommand command);
}
