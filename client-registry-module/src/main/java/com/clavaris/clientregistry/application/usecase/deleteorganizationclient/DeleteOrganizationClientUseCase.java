package com.clavaris.clientregistry.application.usecase.deleteorganizationclient;

@FunctionalInterface
public interface DeleteOrganizationClientUseCase {

  void handle(DeleteOrganizationClientCommand command);
}
