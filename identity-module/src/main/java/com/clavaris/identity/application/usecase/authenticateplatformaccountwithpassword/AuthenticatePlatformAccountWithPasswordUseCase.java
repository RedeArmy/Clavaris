package com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword;

import com.clavaris.identity.domain.model.PlatformAccountId;

@FunctionalInterface
public interface AuthenticatePlatformAccountWithPasswordUseCase {

  /**
   * @return the authenticated {@link PlatformAccountId} on success.
   * @throws InvalidPlatformCredentialsException on any failure.
   */
  PlatformAccountId handle(AuthenticatePlatformAccountWithPasswordCommand command);
}
