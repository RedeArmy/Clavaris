package com.clavaris.clientregistry.application.usecase.deactivateorganizationclient;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/** Same rationale as {@code deactivateplatformclient.DeactivatePlatformClientService}. */
@SuppressWarnings("PMD.LongVariable")
public class DeactivateOrganizationClientService implements DeactivateOrganizationClientUseCase {

  private final OrganizationClientRepository organizationClients;
  private final AuditEventRecorder auditEvents;

  public DeactivateOrganizationClientService(
      final OrganizationClientRepository organizationClients,
      final AuditEventRecorder auditEvents) {
    this.organizationClients = organizationClients;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final DeactivateOrganizationClientCommand command) {
    final OrganizationClient existing =
        organizationClients
            .findByClientId(command.clientId())
            .orElseThrow(() -> new OrganizationClientNotFoundException(command.clientId()));

    organizationClients.save(existing.deactivate());

    auditEvents.write(
        command.actor(),
        "organization_client.deactivated",
        "Organization",
        existing.organizationId().toString(),
        "clientId=" + command.clientId());
  }
}
