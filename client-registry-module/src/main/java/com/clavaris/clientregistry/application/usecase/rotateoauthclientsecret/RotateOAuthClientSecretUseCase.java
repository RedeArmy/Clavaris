package com.clavaris.clientregistry.application.usecase.rotateoauthclientsecret;

@FunctionalInterface
public interface RotateOAuthClientSecretUseCase {

  RotateOAuthClientSecretResult handle(RotateOAuthClientSecretCommand command);
}
