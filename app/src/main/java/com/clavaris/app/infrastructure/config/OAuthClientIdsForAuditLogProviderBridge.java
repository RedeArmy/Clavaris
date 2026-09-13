package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.listoauthclients.ListOAuthClientsUseCase;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.organization.application.usecase.getauditlogfororganization.OAuthClientIdsForAuditLogProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's outbound port — the bridge lives in {@code app}, not either
 * business module, same module-graph reason every other cross-module bridge in this package already
 * documents.
 */
@Component
class OAuthClientIdsForAuditLogProviderBridge implements OAuthClientIdsForAuditLogProvider {

  private final ListOAuthClientsUseCase listOAuthClients;

  /* package */ OAuthClientIdsForAuditLogProviderBridge(
      final ListOAuthClientsUseCase listOAuthClients) {
    this.listOAuthClients = listOAuthClients;
  }

  @Override
  public List<String> oauthClientIds(final UUID organizationId) {
    return listOAuthClients.handle(organizationId).stream()
        .map(OAuthClient::id)
        .map(UUID::toString)
        .toList();
  }
}
