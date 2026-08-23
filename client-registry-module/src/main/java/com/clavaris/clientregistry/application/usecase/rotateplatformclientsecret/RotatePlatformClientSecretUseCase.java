package com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret;

/** Inbound port. */
@FunctionalInterface
public interface RotatePlatformClientSecretUseCase {

  RotatePlatformClientSecretResult handle(RotatePlatformClientSecretCommand command);
}
