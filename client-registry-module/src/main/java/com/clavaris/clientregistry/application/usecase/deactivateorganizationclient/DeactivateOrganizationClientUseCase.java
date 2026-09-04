package com.clavaris.clientregistry.application.usecase.deactivateorganizationclient;

@FunctionalInterface
public interface DeactivateOrganizationClientUseCase {

  void handle(DeactivateOrganizationClientCommand command);
}
