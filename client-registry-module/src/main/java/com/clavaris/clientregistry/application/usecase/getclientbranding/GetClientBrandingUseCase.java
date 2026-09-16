package com.clavaris.clientregistry.application.usecase.getclientbranding;

import com.clavaris.clientregistry.domain.model.ClientBranding;
import java.util.UUID;

@FunctionalInterface
public interface GetClientBrandingUseCase {

  /**
   * Never empty for a real, owned {@code OAuthClient} — returns {@code
   * ClientBranding.define(oauthClientId, null, null, null)}-shaped defaults when no row has ever
   * been saved for it, same "read-side default, never an error" convention {@code
   * GetRedirectPolicyForClientUseCase} already establishes.
   *
   * <p>SDE-III review, 2026-09-15: {@code organizationId} is a real, enforced parameter, not a
   * cosmetic one — see {@code GetClientBrandingService}'s own Javadoc for the cross-tenant read the
   * module-level ownership check this added closes.
   *
   * @throws OAuthClientNotFoundException if no {@code OAuthClient} with {@code oauthClientId}
   *     exists, or one does but belongs to a different Organization than {@code organizationId}
   */
  ClientBranding handle(UUID organizationId, UUID oauthClientId);
}
