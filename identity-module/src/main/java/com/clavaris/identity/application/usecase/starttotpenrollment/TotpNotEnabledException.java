package com.clavaris.identity.application.usecase.starttotpenrollment;

/** The Organization has not turned the authenticator app on (ADR-0040). */
public final class TotpNotEnabledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public TotpNotEnabledException() {
    super("The authenticator app is not enabled for this Organization");
  }
}
