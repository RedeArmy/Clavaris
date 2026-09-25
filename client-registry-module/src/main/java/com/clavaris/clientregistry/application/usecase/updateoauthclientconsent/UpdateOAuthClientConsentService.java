package com.clavaris.clientregistry.application.usecase.updateoauthclientconsent;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/**
 * Same rationale/shape as {@code updateoauthclientgranttypes.UpdateOAuthClientGrantTypesService}.
 */
public class UpdateOAuthClientConsentService implements UpdateOAuthClientConsentUseCase {

  private final OAuthClientRepository oauthClients;
  private final AuditEventRecorder auditEvents;

  public UpdateOAuthClientConsentService(
      final OAuthClientRepository oauthClients, final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final UpdateOAuthClientConsentCommand command) {
    final OAuthClient existing =
        oauthClients
            .findByClientId(command.clientId())
            .filter(found -> found.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new OAuthClientNotFoundException(command.clientId()));

    if (!existing.active()) {
      throw new OAuthClientInactiveException(command.clientId());
    }

    oauthClients.save(existing.updateConsent(command.requireConsent()));

    auditEvents.write(
        command.actor(),
        "oauth_client.consent_updated",
        "Organization",
        existing.organizationId().toString(),
        "clientId=" + command.clientId());
  }
}
