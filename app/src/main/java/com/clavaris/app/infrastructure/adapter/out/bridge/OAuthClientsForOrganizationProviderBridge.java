package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.clientregistry.application.usecase.listoauthclients.ListOAuthClientsUseCase;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.identity.application.usecase.impersonateaccount.OAuthClientSummary;
import com.clavaris.identity.application.usecase.impersonateaccount.OAuthClientsForOrganizationProvider;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Adapts client-registry-module's {@link ListOAuthClientsUseCase} to identity-module's own {@link
 * OAuthClientsForOrganizationProvider} port — same module-independence-crossing bridge pattern
 * {@link OrganizationForClientResolverBridge} already establishes for the reverse direction.
 */
@Component
class OAuthClientsForOrganizationProviderBridge implements OAuthClientsForOrganizationProvider {

  private final ListOAuthClientsUseCase listOAuthClients;

  /* package */ OAuthClientsForOrganizationProviderBridge(
      final ListOAuthClientsUseCase listOAuthClients) {
    this.listOAuthClients = listOAuthClients;
  }

  @Override
  public List<OAuthClientSummary> forOrganization(final OrganizationId organizationId) {
    return listOAuthClients.handle(organizationId.value()).stream()
        .filter(OAuthClient::active)
        .map(
            client ->
                new OAuthClientSummary(client.id(), client.clientId(), client.allowedScopes()))
        .toList();
  }
}
