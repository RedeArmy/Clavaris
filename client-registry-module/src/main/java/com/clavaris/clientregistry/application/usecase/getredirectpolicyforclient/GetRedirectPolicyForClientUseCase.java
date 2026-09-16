package com.clavaris.clientregistry.application.usecase.getredirectpolicyforclient;

import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import java.util.UUID;

@FunctionalInterface
public interface GetRedirectPolicyForClientUseCase {

  /**
   * Never empty for a real, owned {@code OAuthClient} — returns {@link RedirectPolicy#unconfigured}
   * when no row has ever been saved for it, the one place that default-supplying logic lives
   * (reused by both the admin-API {@code GET} controller and identity-module's own read port
   * bridge, same "shared default logic" precedent {@code
   * GetAccountAuthenticationPolicyForOrganizationUseCase} already establishes).
   *
   * <p>SDE-III review, 2026-09-15: {@code organizationId} is a real, enforced parameter — see
   * {@code GetRedirectPolicyForClientService}'s own Javadoc for the cross-tenant read this closes.
   *
   * @throws OAuthClientNotFoundException if no {@code OAuthClient} with {@code oauthClientId}
   *     exists, or one does but belongs to a different Organization than {@code organizationId}
   */
  RedirectPolicy handle(UUID organizationId, UUID oauthClientId);
}
