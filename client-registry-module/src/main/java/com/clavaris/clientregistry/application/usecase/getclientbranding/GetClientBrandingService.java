package com.clavaris.clientregistry.application.usecase.getclientbranding;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.setclientbranding.ClientBrandingRepository;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import java.util.UUID;

/**
 * Read side of the branding surface. Depends on {@code ClientBrandingRepository} directly (the same
 * port {@code SetClientBrandingService} writes through) — same "shared port, separate use-case
 * folders" precedent {@code GetRedirectPolicyForClientService} already establishes.
 *
 * <p>SDE-III review, 2026-09-15 — real gap found and closed: this class used to take a bare {@code
 * oauthClientId} with no ownership check of its own, relying entirely on {@code
 * OrganizationClientOwnershipFilter} (app module) — a filter whose own allowlist never actually
 * covered this endpoint, and which in any case only ever verifies the path's {@code organizationId}
 * against the caller's token claim, never that {@code oauthClientId} itself belongs to that
 * Organization. Nothing in this module stopped a caller from pairing a valid {@code organizationId}
 * with a different Organization's own {@code oauthClientId} and reading back that Organization's
 * real branding (logo URL, primary color, display name) — a real cross-tenant read, defended only
 * by app-layer plumbing that was never actually wired for this route, not by the module itself. Now
 * verifies ownership the same way {@code SetClientBrandingService} already does on the write side.
 */
public class GetClientBrandingService implements GetClientBrandingUseCase {

  private final OAuthClientRepository oauthClients;
  private final ClientBrandingRepository brandings;

  public GetClientBrandingService(
      final OAuthClientRepository oauthClients, final ClientBrandingRepository brandings) {
    this.oauthClients = oauthClients;
    this.brandings = brandings;
  }

  @Override
  public ClientBranding handle(final UUID organizationId, final UUID oauthClientId) {
    oauthClients
        .findById(oauthClientId)
        .filter(found -> found.organizationId().equals(organizationId))
        .orElseThrow(() -> new OAuthClientNotFoundException(oauthClientId));

    return brandings
        .findByOAuthClientId(oauthClientId)
        .orElseGet(() -> ClientBranding.unconfigured(oauthClientId));
  }
}
