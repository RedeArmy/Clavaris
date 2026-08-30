package com.clavaris.identity.application.usecase.confirmpendingplatformsociallink;

/**
 * {@link
 * com.clavaris.identity.application.usecase.confirmpendingsociallink.InvalidPendingSocialLinkException}'s
 * platform-tier sibling.
 */
public final class InvalidPendingPlatformSocialLinkException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidPendingPlatformSocialLinkException() {
    super("Invalid or expired social link confirmation token");
  }
}
