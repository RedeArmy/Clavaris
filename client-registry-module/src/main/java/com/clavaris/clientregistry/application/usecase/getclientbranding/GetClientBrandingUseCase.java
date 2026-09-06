package com.clavaris.clientregistry.application.usecase.getclientbranding;

import com.clavaris.clientregistry.domain.model.ClientBranding;
import java.util.UUID;

@FunctionalInterface
public interface GetClientBrandingUseCase {

  /**
   * Never empty — returns {@code ClientBranding.define(oauthClientId, null, null, null)}-shaped
   * defaults when no row has ever been saved for this {@code OAuthClient}, same "read-side default,
   * never an error" convention {@code GetRedirectPolicyForClientUseCase} already establishes.
   */
  ClientBranding handle(UUID oauthClientId);
}
