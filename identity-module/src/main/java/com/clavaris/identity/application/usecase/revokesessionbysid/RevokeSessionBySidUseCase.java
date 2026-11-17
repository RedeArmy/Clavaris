package com.clavaris.identity.application.usecase.revokesessionbysid;

/**
 * TD-SEC-100: what RP-Initiated Logout has to do to the tokens. Logging out of the hosted login
 * ended the browser session and nothing else, so the refresh token the client held kept minting.
 */
@FunctionalInterface
public interface RevokeSessionBySidUseCase {

  /**
   * Ends the Session whose ID tokens carry {@code sid}, with every refresh token of it.
   *
   * @return true when such a Session of this Organization was found and ended
   */
  boolean handle(RevokeSessionBySidCommand command);
}
