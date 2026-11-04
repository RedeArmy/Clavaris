package com.clavaris.identity.application.usecase.starttotpenrollment;

/** ADR-0040: begins adding an authenticator app, handing back the secret to put in it. */
@FunctionalInterface
public interface StartTotpEnrollmentUseCase {

  /**
   * @throws TotpNotEnabledException if the Organization has not turned the authenticator app on
   * @throws TotpAlreadyEnrolledException if the Account already has a confirmed one
   */
  TotpEnrollmentStart handle(StartTotpEnrollmentCommand command);
}
