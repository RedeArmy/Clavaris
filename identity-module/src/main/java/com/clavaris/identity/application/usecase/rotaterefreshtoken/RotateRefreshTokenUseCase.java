package com.clavaris.identity.application.usecase.rotaterefreshtoken;

/**
 * Inbound port — BR-ID-03: single-use rotation, with reuse of an already-rotated token revoking
 * every active token for the account.
 */
@FunctionalInterface
public interface RotateRefreshTokenUseCase {

  /**
   * @throws InvalidRefreshTokenException the presented token is unknown or naturally expired
   * @throws RefreshTokenReuseDetectedException the presented token was already rotated away or
   *     revoked — every active token for the account has just been revoked as a side effect of this
   *     call, before it throws
   */
  RotateRefreshTokenResult handle(RotateRefreshTokenCommand command);
}
