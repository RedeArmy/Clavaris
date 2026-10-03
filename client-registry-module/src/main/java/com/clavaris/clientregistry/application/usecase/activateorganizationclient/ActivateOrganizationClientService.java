package com.clavaris.clientregistry.application.usecase.activateorganizationclient;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientNotFoundException;
import com.clavaris.clientregistry.application.usecase.createorganizationclient.OrganizationClientRepository;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.common.application.port.AuditEventRecorder;
import org.springframework.transaction.annotation.Transactional;

/** Mirror of {@code DeactivateOrganizationClientService}; the secret is left exactly as it was. */
@SuppressWarnings("PMD.LongVariable")
public class ActivateOrganizationClientService implements ActivateOrganizationClientUseCase {

  private final OrganizationClientRepository organizationClients;
  private final AuditEventRecorder auditEvents;

  public ActivateOrganizationClientService(
      final OrganizationClientRepository organizationClients,
      final AuditEventRecorder auditEvents) {
    this.organizationClients = organizationClients;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final ActivateOrganizationClientCommand command) {
    final OrganizationClient existing =
        organizationClients
            .findByClientId(command.clientId())
            .filter(found -> found.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new OrganizationClientNotFoundException(command.clientId()));

    organizationClients.save(existing.activate());

    auditEvents.write(
        command.actor(),
        "organization_client.activated",
        "Organization",
        existing.organizationId().toString(),
        "clientId=" + command.clientId());
  }
}
