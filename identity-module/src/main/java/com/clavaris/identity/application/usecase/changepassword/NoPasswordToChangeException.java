package com.clavaris.identity.application.usecase.changepassword;

/**
 * The Account has no password credential (it signs in with a social provider, a passkey or an email
 * code), so there is nothing to change; setting a first password is the forgot-password flow's job.
 */
public final class NoPasswordToChangeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NoPasswordToChangeException() {
    super("This account has no password to change");
  }
}
