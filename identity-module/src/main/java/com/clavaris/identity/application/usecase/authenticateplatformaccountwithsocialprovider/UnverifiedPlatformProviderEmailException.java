package com.clavaris.identity.application.usecase.authenticateplatformaccountwithsocialprovider;

/**
 * {@link
 * com.clavaris.identity.application.usecase.authenticatewithsocialprovider.UnverifiedProviderEmailException}'s
 * platform-tier sibling.
 */
public final class UnverifiedPlatformProviderEmailException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnverifiedPlatformProviderEmailException() {
    super("Social provider did not report a verified email");
  }
}
