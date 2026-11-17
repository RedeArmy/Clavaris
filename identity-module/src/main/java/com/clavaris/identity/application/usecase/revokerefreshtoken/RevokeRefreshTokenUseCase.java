package com.clavaris.identity.application.usecase.revokerefreshtoken;

/**
 * TD-SEC-100: RFC 7009 revocation of a refresh token. Spring Authorization Server's own revocation
 * only reaches the tokens it stores, and a refresh token's source of truth here is {@code
 * refresh_tokens} (BR-ID-03), so without this a revocation was accepted, logged and had no effect.
 */
@FunctionalInterface
public interface RevokeRefreshTokenUseCase {

  /**
   * Ends the Session the presented token belongs to, so no token of it can mint another.
   *
   * @return true when the token was a refresh token of this Organization and its Session was ended;
   *     false for anything else (unknown, another Organization's, an access token), which the
   *     caller must answer exactly like a success: RFC 7009 §2.2 gives no way to tell them apart.
   */
  boolean handle(RevokeRefreshTokenCommand command);
}
