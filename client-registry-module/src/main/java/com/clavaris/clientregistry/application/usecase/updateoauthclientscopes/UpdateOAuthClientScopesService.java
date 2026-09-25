package com.clavaris.clientregistry.application.usecase.updateoauthclientscopes;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/**
 * Same rationale/shape as {@code updateoauthclientgranttypes.UpdateOAuthClientGrantTypesService}.
 */
public class UpdateOAuthClientScopesService implements UpdateOAuthClientScopesUseCase {

  private final OAuthClientRepository oauthClients;
  private final AuditEventRecorder auditEvents;

  public UpdateOAuthClientScopesService(
      final OAuthClientRepository oauthClients, final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final UpdateOAuthClientScopesCommand command) {
    final OAuthClient existing =
        oauthClients
            .findByClientId(command.clientId())
            .filter(found -> found.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new OAuthClientNotFoundException(command.clientId()));

    if (!existing.active()) {
      throw new OAuthClientInactiveException(command.clientId());
    }

    oauthClients.save(existing.updateScopes(command.allowedScopes()));

    auditEvents.write(
        command.actor(),
        "oauth_client.scopes_updated",
        "Organization",
        existing.organizationId().toString(),
        "clientId=" + command.clientId());
  }
}
