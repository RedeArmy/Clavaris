package com.clavaris.clientregistry.application.usecase.bootstrapplatformclient;

import com.clavaris.clientregistry.domain.model.PlatformClient;
import com.clavaris.clientregistry.domain.model.PlatformScopes;

/**
 * Orchestration for {@link BootstrapPlatformClientUseCase} — no Spring dependency at all: unlike
 * RegisterAccountService, this doesn't need @Transactional (a single save, no multi-row invariant
 * to protect).
 */
public class BootstrapPlatformClientService implements BootstrapPlatformClientUseCase {

  private final PlatformClientRepository platformClients;
  private final ClientSecretHasher hasher;

  public BootstrapPlatformClientService(
      final PlatformClientRepository platformClients, final ClientSecretHasher hasher) {
    this.platformClients = platformClients;
    this.hasher = hasher;
  }

  @Override
  public void handle(final BootstrapPlatformClientCommand command) {
    // BR-PLATFORM-03: idempotent — a redeploy/restart must not fail, or worse, silently create a
    // second row / rotate the secret out from under whatever already trusts the existing one.
    if (platformClients.existsByClientId(command.clientId())) {
      return;
    }

    final PlatformClient platformClient =
        PlatformClient.register(
            command.clientId(),
            hasher.hash(command.rawClientSecret()),
            PlatformScopes.BOOTSTRAP_DEFAULT);
    platformClients.save(platformClient);
  }
}
