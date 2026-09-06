package com.clavaris.clientregistry.application.usecase.getclientbranding;

import com.clavaris.clientregistry.application.usecase.setclientbranding.ClientBrandingRepository;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import java.util.UUID;

/**
 * Read side of the branding surface. Depends on {@code ClientBrandingRepository} directly (the same
 * port {@code SetClientBrandingService} writes through) — same "shared port, separate use-case
 * folders" precedent {@code GetRedirectPolicyForClientService} already establishes.
 */
public class GetClientBrandingService implements GetClientBrandingUseCase {

  private final ClientBrandingRepository brandings;

  public GetClientBrandingService(final ClientBrandingRepository brandings) {
    this.brandings = brandings;
  }

  @Override
  public ClientBranding handle(final UUID oauthClientId) {
    return brandings
        .findByOAuthClientId(oauthClientId)
        .orElseGet(() -> ClientBranding.unconfigured(oauthClientId));
  }
}
