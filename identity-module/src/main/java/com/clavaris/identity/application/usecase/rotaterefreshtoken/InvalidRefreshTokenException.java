package com.clavaris.identity.application.usecase.rotaterefreshtoken;

/**
 * A presented refresh token that is unknown (never issued by this system) or naturally expired — an
 * ordinary invalid_grant, not the BR-ID-03 reuse signal {@link RefreshTokenReuseDetectedException}
 * carries. Kept as a separate type specifically so the web adapter can map the two to the same RFC
 * 6749 §5.2 {@code invalid_grant} response while the distinction still exists internally for
 * anything that needs it (tests, future observability).
 */
public final class InvalidRefreshTokenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidRefreshTokenException() {
    super("Invalid or expired refresh token");
  }
}
