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

  // Code review finding: same rationale as the tenant-tier sibling's own identical constructor —
  // a losing confirmation in the two-active-links race surfaces as a real constraint violation,
  // preserved here as the cause.
  public InvalidPendingPlatformSocialLinkException(final Throwable cause) {
    super("Invalid or expired social link confirmation token", cause);
  }
}
