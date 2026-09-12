package com.clavaris.clientregistry.application.usecase.deactivateoauthclient;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/** Same rationale as {@code deactivateorganizationclient.DeactivateOrganizationClientService}. */
public class DeactivateOAuthClientService implements DeactivateOAuthClientUseCase {

  private final OAuthClientRepository oauthClients;
  private final AuditEventRecorder auditEvents;

  public DeactivateOAuthClientService(
      final OAuthClientRepository oauthClients, final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final DeactivateOAuthClientCommand command) {
    final OAuthClient existing =
        oauthClients
            .findByClientId(command.clientId())
            .orElseThrow(() -> new OAuthClientNotFoundException(command.clientId()));

    oauthClients.save(existing.deactivate());

    auditEvents.write(
        command.actor(),
        "oauth_client.deactivated",
        "Organization",
        existing.organizationId().toString(),
        "clientId=" + command.clientId());
  }
}
