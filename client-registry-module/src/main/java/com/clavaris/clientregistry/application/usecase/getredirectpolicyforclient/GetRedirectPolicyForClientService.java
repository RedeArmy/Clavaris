package com.clavaris.clientregistry.application.usecase.getredirectpolicyforclient;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.RedirectPolicyRepository;
import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import java.util.UUID;

/**
 * Read side of the redirect-policy surface. Depends on {@code RedirectPolicyRepository} directly
 * (the same port {@code SetRedirectPolicyForClientService} writes through) rather than duplicating
 * a second repository interface for the same table — same "shared port, separate use-case folders"
 * precedent {@code GetAccountAuthenticationPolicyForOrganizationService} already establishes.
 *
 * <p>SDE-III review, 2026-09-15 — real gap found and closed: same shape and same fix as {@code
 * GetClientBrandingService}/{@code GetClientDomainConfigService}'s own identical additions — this
 * class used to take a bare {@code oauthClientId} with no ownership check of its own, relying
 * entirely on {@code OrganizationClientOwnershipFilter} (app module), whose allowlist never
 * actually covered this endpoint and which in any case only ever verifies the path's {@code
 * organizationId}, never that {@code oauthClientId} itself belongs to it. A cross-tenant read here
 * could reveal another Organization's configured fallback/force redirect URLs. Now verifies
 * ownership the same way {@code SetRedirectPolicyForClientService} already does on the write side.
 */
public class GetRedirectPolicyForClientService implements GetRedirectPolicyForClientUseCase {

  private final OAuthClientRepository oauthClients;
  private final RedirectPolicyRepository policies;

  public GetRedirectPolicyForClientService(
      final OAuthClientRepository oauthClients, final RedirectPolicyRepository policies) {
    this.oauthClients = oauthClients;
    this.policies = policies;
  }

  @Override
  public RedirectPolicy handle(final UUID organizationId, final UUID oauthClientId) {
    oauthClients
        .findById(oauthClientId)
        .filter(found -> found.organizationId().equals(organizationId))
        .orElseThrow(() -> new OAuthClientNotFoundException(oauthClientId));

    return policies
        .findByOAuthClientId(oauthClientId)
        .orElseGet(() -> RedirectPolicy.unconfigured(oauthClientId));
  }
}
