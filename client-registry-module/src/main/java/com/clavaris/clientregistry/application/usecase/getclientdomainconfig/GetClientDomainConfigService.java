package com.clavaris.clientregistry.application.usecase.getclientdomainconfig;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import java.util.UUID;

/**
 * Read side of the domain-config surface. Depends on {@code ClientDomainConfigRepository} directly
 * (the same port {@code RequestClientDomainConfigService}/{@code
 * VerifyClientDomainOwnershipService} write through) — same "shared port, separate use-case
 * folders" precedent {@code GetClientBrandingService} already establishes.
 *
 * <p>SDE-III review, 2026-09-15 — real gap found and closed: same shape and same fix as {@code
 * GetClientBrandingService}'s own identical addition — this class used to take a bare {@code
 * oauthClientId} with no ownership check of its own, relying entirely on {@code
 * OrganizationClientOwnershipFilter} (app module), whose allowlist never actually covered this
 * endpoint and which in any case only ever verifies the path's {@code organizationId}, never that
 * {@code oauthClientId} itself belongs to it. A cross-tenant read here could reveal another
 * Organization's custom-domain hostname and verification status. Now verifies ownership the same
 * way {@code RequestClientDomainConfigService} already does on the write side.
 */
public class GetClientDomainConfigService implements GetClientDomainConfigUseCase {

  private final OAuthClientRepository oauthClients;
  private final ClientDomainConfigRepository domainConfigs;

  public GetClientDomainConfigService(
      final OAuthClientRepository oauthClients, final ClientDomainConfigRepository domainConfigs) {
    this.oauthClients = oauthClients;
    this.domainConfigs = domainConfigs;
  }

  @Override
  public ClientDomainConfig handle(final UUID organizationId, final UUID oauthClientId) {
    oauthClients
        .findById(oauthClientId)
        .filter(found -> found.organizationId().equals(organizationId))
        .orElseThrow(() -> new OAuthClientNotFoundException(oauthClientId));

    return domainConfigs
        .findByOAuthClientId(oauthClientId)
        .orElseGet(() -> ClientDomainConfig.unconfigured(oauthClientId));
  }
}
