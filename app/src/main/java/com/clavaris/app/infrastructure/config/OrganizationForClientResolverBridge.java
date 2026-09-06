package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.identity.application.usecase.resolveorganizationforclient.OrganizationForClientResolver;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Adapts client-registry-module's {@code OAuthClientRepository} to identity-module's own {@link
 * OrganizationForClientResolver} port — same module-independence-crossing bridge pattern {@link
 * ClientBrandingProviderBridge}/{@link RedirectUrlResolverBridge} already establish for an
 * identical need.
 */
@Component
class OrganizationForClientResolverBridge implements OrganizationForClientResolver {

  private final OAuthClientRepository oauthClients;

  /* package */ OrganizationForClientResolverBridge(final OAuthClientRepository oauthClients) {
    this.oauthClients = oauthClients;
  }

  @Override
  public Optional<OrganizationId> resolve(final String clientId) {
    return oauthClients.findByClientId(clientId).map(c -> new OrganizationId(c.organizationId()));
  }
}
