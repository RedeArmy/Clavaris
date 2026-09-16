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
            // SDE-III review, 2026-09-15: a null organizationId is the platform-tier caller's own
            // deliberate, unscoped reach (see this command's own Javadoc) — every other caller
            // must match, collapsing a cross-tenant mismatch into the same 404 a genuinely missing
            // clientId already produces, same BR-ORG-02-style anti-enumeration discipline every
            // other ownership check in this module already follows.
            .filter(
                found ->
                    command.organizationId() == null
                        || found.organizationId().equals(command.organizationId()))
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
