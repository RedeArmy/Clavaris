package com.clavaris.clientregistry.application.usecase.activateorganizationclient;

@FunctionalInterface
public interface ActivateOrganizationClientUseCase {

  void handle(ActivateOrganizationClientCommand command);
}
