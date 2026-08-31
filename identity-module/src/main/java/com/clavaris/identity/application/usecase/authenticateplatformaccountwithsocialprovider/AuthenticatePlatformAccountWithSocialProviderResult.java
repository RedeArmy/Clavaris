package com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider;

import com.clavaris.identity.domain.model.PlatformAccountId;

/**
 * {@link
 * com.clavaris.identity.application.usecase.authenticatewithsocialprovider.AuthenticateWithSocialProviderResult}'s
 * platform-tier sibling — same rationale for a sealed two-outcome result.
 */
public sealed interface AuthenticatePlatformAccountWithSocialProviderResult {

  record LoggedIn(PlatformAccountId platformAccountId)
      implements AuthenticatePlatformAccountWithSocialProviderResult {}

  record ConfirmationRequired() implements AuthenticatePlatformAccountWithSocialProviderResult {}
}
