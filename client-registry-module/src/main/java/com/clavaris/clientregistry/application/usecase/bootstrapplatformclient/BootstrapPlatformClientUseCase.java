package com.clavaris.clientregistry.application.usecase.bootstrapplatformclient;

/**
 * Inbound port. BR-PLATFORM-03: the first {@code PlatformClient} is seeded from
 * deployment-environment configuration via an idempotent startup check — never a "break glass" HTTP
 * endpoint, never a credential shipped in code. The infrastructure runner that calls this at
 * startup (not this interface) is what makes it "never an HTTP endpoint" true.
 */
@FunctionalInterface
public interface BootstrapPlatformClientUseCase {

  /**
   * Idempotent: does nothing if a {@code PlatformClient} with this {@code clientId} already exists.
   */
  void handle(BootstrapPlatformClientCommand command);
}
