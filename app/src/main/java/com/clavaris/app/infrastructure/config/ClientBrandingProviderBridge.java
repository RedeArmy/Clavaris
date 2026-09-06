package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.getclientbranding.GetClientBrandingUseCase;
import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingSnapshot;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Adapts client-registry-module's {@code OAuthClientRepository}/{@code GetClientBrandingUseCase} to
 * identity-module's own {@link ClientBrandingProvider} port — same module-independence-crossing
 * bridge pattern {@link RedirectUrlResolverBridge} already establishes for an identical need.
 */
@Component
class ClientBrandingProviderBridge implements ClientBrandingProvider {

  private final OAuthClientRepository oauthClients;
  private final GetClientBrandingUseCase getClientBranding;

  /* package */ ClientBrandingProviderBridge(
      final OAuthClientRepository oauthClients, final GetClientBrandingUseCase getClientBranding) {
    this.oauthClients = oauthClients;
    this.getClientBranding = getClientBranding;
  }

  // Two exits (no usable client context / a resolved snapshot) — same rationale
  // RedirectUrlResolverBridge's own identical suppression documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public ClientBrandingSnapshot brandingFor(
      final OrganizationId organizationId, final String clientId) {
    if (clientId == null) {
      return ClientBrandingSnapshot.unconfigured();
    }
    final Optional<OAuthClient> maybeClient = oauthClients.findByClientId(clientId);
    // BR-ORG-02-style cross-tenant defence in depth — same convention RedirectUrlResolverBridge's
    // own identical check documents.
    if (maybeClient.isEmpty()
        || !maybeClient.get().organizationId().equals(organizationId.value())) {
      return ClientBrandingSnapshot.unconfigured();
    }
    final ClientBranding branding = getClientBranding.handle(maybeClient.get().id());
    return new ClientBrandingSnapshot(
        branding.logoUrl(), branding.primaryColor(), branding.applicationDisplayName());
  }
}
