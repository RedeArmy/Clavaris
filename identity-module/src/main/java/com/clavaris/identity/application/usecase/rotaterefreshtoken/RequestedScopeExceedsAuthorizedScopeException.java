package com.clavaris.identity.application.usecase.rotaterefreshtoken;

/**
 * RFC 6749 §6: a refresh request must never be granted a scope broader than what the original
 * authorization actually granted. Thrown from inside {@link RotateRefreshTokenService}'s own
 * transaction, before any mutation happens — checking this in the infrastructure layer, after
 * {@link RotateRefreshTokenUseCase} had already rotated the token, was a real bug this class's own
 * commit fixes: an over-scoped request would silently consume/rotate the presented token and still
 * return an error, leaving the caller with no valid refresh token at all despite the 400 response
 * implying nothing had changed.
 */
public final class RequestedScopeExceedsAuthorizedScopeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RequestedScopeExceedsAuthorizedScopeException() {
    super("Requested scope exceeds what was originally authorized");
  }
}
