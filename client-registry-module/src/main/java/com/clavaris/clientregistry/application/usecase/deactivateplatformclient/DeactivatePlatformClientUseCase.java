package com.clavaris.clientregistry.application.usecase.deactivateplatformclient;

/** Inbound port. */
@FunctionalInterface
public interface DeactivatePlatformClientUseCase {

  void handle(DeactivatePlatformClientCommand command);
}
