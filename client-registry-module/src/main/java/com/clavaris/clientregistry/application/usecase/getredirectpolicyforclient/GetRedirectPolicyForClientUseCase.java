package com.clavaris.clientregistry.application.usecase.getredirectpolicyforclient;

import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import java.util.UUID;

@FunctionalInterface
public interface GetRedirectPolicyForClientUseCase {

  /**
   * Never empty — returns {@link RedirectPolicy#unconfigured} when no row has ever been saved for
   * this {@code OAuthClient}, the one place that default-supplying logic lives (reused by both the
   * admin-API {@code GET} controller and identity-module's own read port bridge, same "shared
   * default logic" precedent {@code GetAccountAuthenticationPolicyForOrganizationUseCase} already
   * establishes).
   */
  RedirectPolicy handle(UUID oauthClientId);
}
