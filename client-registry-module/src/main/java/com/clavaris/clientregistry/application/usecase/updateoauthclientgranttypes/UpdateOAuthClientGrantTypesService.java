package com.clavaris.clientregistry.application.usecase.updateoauthclientgranttypes;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/**
 * Same rationale/shape as {@code
 * updateoauthclientredirectsettings.UpdateOAuthClientRedirectSettingsService}.
 */
public class UpdateOAuthClientGrantTypesService implements UpdateOAuthClientGrantTypesUseCase {

  private final OAuthClientRepository oauthClients;
  private final AuditEventRecorder auditEvents;

  public UpdateOAuthClientGrantTypesService(
      final OAuthClientRepository oauthClients, final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final UpdateOAuthClientGrantTypesCommand command) {
    final OAuthClient existing =
        oauthClients
            .findByClientId(command.clientId())
            .filter(found -> found.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new OAuthClientNotFoundException(command.clientId()));

    if (!existing.active()) {
      throw new OAuthClientInactiveException(command.clientId());
    }

    oauthClients.save(existing.updateGrantTypes(command.allowedGrantTypes()));

    auditEvents.write(
        command.actor(),
        "oauth_client.grant_types_updated",
        "Organization",
        existing.organizationId().toString(),
        "clientId=" + command.clientId());
  }
}
