package com.clavaris.clientregistry.application.usecase.createorganizationclient;

@FunctionalInterface
public interface CreateOrganizationClientUseCase {

  CreateOrganizationClientResult handle(CreateOrganizationClientCommand command);
}
