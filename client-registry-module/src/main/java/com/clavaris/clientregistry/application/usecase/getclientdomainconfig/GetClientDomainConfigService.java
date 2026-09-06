package com.clavaris.clientregistry.application.usecase.getclientdomainconfig;

import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import java.util.UUID;

/**
 * Read side of the domain-config surface. Depends on {@code ClientDomainConfigRepository} directly
 * (the same port {@code RequestClientDomainConfigService}/{@code
 * VerifyClientDomainOwnershipService} write through) — same "shared port, separate use-case
 * folders" precedent {@code GetClientBrandingService} already establishes.
 */
public class GetClientDomainConfigService implements GetClientDomainConfigUseCase {

  private final ClientDomainConfigRepository domainConfigs;

  public GetClientDomainConfigService(final ClientDomainConfigRepository domainConfigs) {
    this.domainConfigs = domainConfigs;
  }

  @Override
  public ClientDomainConfig handle(final UUID oauthClientId) {
    return domainConfigs
        .findByOAuthClientId(oauthClientId)
        .orElseGet(() -> ClientDomainConfig.unconfigured(oauthClientId));
  }
}
