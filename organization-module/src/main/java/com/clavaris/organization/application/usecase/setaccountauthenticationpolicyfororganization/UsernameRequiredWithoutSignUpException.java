package com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization;

/**
 * {@code usernameRequired}/{@code usernameSignInEnabled} are only meaningful when {@code
 * usernameSignUpEnabled} is also {@code true} — same "flag the operator mistake, don't let it
 * persist silently" reasoning {@code SocialLoginEnabledWithNoProvidersException} already
 * establishes for its own sibling policy.
 */
public final class UsernameRequiredWithoutSignUpException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UsernameRequiredWithoutSignUpException() {
    super("usernameRequired/usernameSignInEnabled require usernameSignUpEnabled=true");
  }
}
