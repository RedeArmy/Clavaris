package com.clavaris.clientregistry.application.usecase.activateoauthclient;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/** Same rationale as {@code deactivateoauthclient.DeactivateOAuthClientService}. */
public class ActivateOAuthClientService implements ActivateOAuthClientUseCase {

  private final OAuthClientRepository oauthClients;
  private final AuditEventRecorder auditEvents;

  public ActivateOAuthClientService(
      final OAuthClientRepository oauthClients, final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final ActivateOAuthClientCommand command) {
    final OAuthClient existing =
        oauthClients
            .findByClientId(command.clientId())
            // Same cross-tenant-mismatch-collapses-to-404 reasoning as
            // DeactivateOAuthClientService's own identical check.
            .filter(found -> found.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new OAuthClientNotFoundException(command.clientId()));

    oauthClients.save(existing.activate());

    auditEvents.write(
        command.actor(),
        "oauth_client.activated",
        "Organization",
        existing.organizationId().toString(),
        "clientId=" + command.clientId());
  }
}
