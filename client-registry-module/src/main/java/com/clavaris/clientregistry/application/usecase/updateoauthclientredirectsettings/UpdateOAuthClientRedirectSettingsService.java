package com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.application.port.AuditEventRecorder;

/**
 * Same ownership-verification/audit shape as {@code
 * deactivateoauthclient.DeactivateOAuthClientService}.
 */
public class UpdateOAuthClientRedirectSettingsService
    implements UpdateOAuthClientRedirectSettingsUseCase {

  private final OAuthClientRepository oauthClients;
  private final AuditEventRecorder auditEvents;

  public UpdateOAuthClientRedirectSettingsService(
      final OAuthClientRepository oauthClients, final AuditEventRecorder auditEvents) {
    this.oauthClients = oauthClients;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final UpdateOAuthClientRedirectSettingsCommand command) {
    final OAuthClient existing =
        oauthClients
            .findByClientId(command.clientId())
            // Same cross-tenant-mismatch-collapses-to-404 reasoning as
            // DeactivateOAuthClientService's own identical check.
            .filter(found -> found.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new OAuthClientNotFoundException(command.clientId()));

    oauthClients.save(
        existing.updateRedirectSettings(command.redirectUris(), command.postLogoutRedirectUris()));

    auditEvents.write(
        command.actor(),
        "oauth_client.redirect_settings_updated",
        "Organization",
        existing.organizationId().toString(),
        "clientId=" + command.clientId());
  }
}
