package com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret;

@FunctionalInterface
public interface RotateOrganizationClientSecretUseCase {

  RotateOrganizationClientSecretResult handle(RotateOrganizationClientSecretCommand command);
}
