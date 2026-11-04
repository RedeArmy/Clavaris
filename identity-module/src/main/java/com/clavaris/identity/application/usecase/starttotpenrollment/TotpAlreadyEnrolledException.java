package com.clavaris.identity.application.usecase.starttotpenrollment;

/** The Account already has an authenticator app; it has to be removed before another is added. */
public final class TotpAlreadyEnrolledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public TotpAlreadyEnrolledException() {
    super("This account already has an authenticator app");
  }
}
