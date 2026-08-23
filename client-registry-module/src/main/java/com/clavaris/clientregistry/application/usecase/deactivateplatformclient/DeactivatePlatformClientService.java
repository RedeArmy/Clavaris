package com.clavaris.clientregistry.application.usecase.deactivateplatformclient;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
import com.clavaris.clientregistry.domain.model.PlatformClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/**
 * TD-SEC-018: the self-service half of PlatformClient compromise recovery this codebase didn't have
 * — {@code PlatformRegisteredClientRepository} (app module) is what actually enforces the
 * consequence, treating an inactive client as not-found the moment the next {@code
 * client_credentials} exchange is attempted. Idempotent by design: deactivating an already-
 * inactive client is a no-op save, not an error — same "the end state is what matters" posture
 * {@code ActivateSigningKeyForOrganizationService} already takes for re-activating an unchanged
 * kid.
 */
public class DeactivatePlatformClientService implements DeactivatePlatformClientUseCase {

  private final PlatformClientRepository platformClients;
  private final AuditEventRecorder auditEvents;

  public DeactivatePlatformClientService(
      final PlatformClientRepository platformClients, final AuditEventRecorder auditEvents) {
    this.platformClients = platformClients;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final DeactivatePlatformClientCommand command) {
    final PlatformClient existing =
        platformClients
            .findByClientId(command.clientId())
            .orElseThrow(() -> new PlatformClientNotFoundException(command.clientId()));

    platformClients.save(existing.deactivate());

    auditEvents.record(
        command.actor(), "platform_client.deactivated", "PlatformClient", command.clientId(), null);
  }
}
