package com.clavaris.identity.application.usecase.rotaterefreshtoken;

/**
 * BR-ID-03: thrown after the full revocation cascade has already completed synchronously — by the
 * time a caller catches this, every active token for the account is already gone, not merely
 * scheduled to be. The web adapter maps this to the same RFC 6749 §5.2 {@code invalid_grant}
 * response {@link InvalidRefreshTokenException} gets — from the presenter's point of view the two
 * must be indistinguishable, for the same anti-enumeration reasoning {@code
 * InvalidCredentialsException} already documents for login.
 */
public final class RefreshTokenReuseDetectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RefreshTokenReuseDetectedException() {
    super("Refresh token reuse detected — every active token for this account has been revoked");
  }
}
