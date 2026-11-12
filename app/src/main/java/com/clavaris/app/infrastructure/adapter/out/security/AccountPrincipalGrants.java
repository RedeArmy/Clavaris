package com.clavaris.app.infrastructure.adapter.out.security;

import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * TD-ARCH-051: which grants issue a token whose principal is an {@code Account}, as opposed to a
 * client acting for itself ({@code client_credentials}). The claims that describe the person
 * (workspace role, picture, email, phone) are added only for these, and four customizers used to
 * repeat the test with the two grants that existed when they were written. Impersonation mints a
 * token for an account under the token-exchange grant, so that grant was silently left out and the
 * impersonated token lacked the claims a real sign-in carries. One definition, so the next grant
 * that carries an account is added here and nowhere else.
 */
public final class AccountPrincipalGrants {

  /**
   * RFC 8693's own name for a token minted for one identity while another acts: what impersonation
   * issues.
   */
  public static final AuthorizationGrantType TOKEN_EXCHANGE =
      new AuthorizationGrantType("urn:ietf:params:oauth:grant-type:token-exchange");

  private AccountPrincipalGrants() {
    // Static helper only — no instance state.
  }

  /** Whether a token issued under {@code grantType} has an Account as its principal. */
  public static boolean hasAccountPrincipal(final AuthorizationGrantType grantType) {
    return AuthorizationGrantType.AUTHORIZATION_CODE.equals(grantType)
        || AuthorizationGrantType.REFRESH_TOKEN.equals(grantType)
        || TOKEN_EXCHANGE.equals(grantType);
  }
}
