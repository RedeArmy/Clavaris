package com.clavaris.identity.application.usecase.confirmtotpenrollment;

import java.util.List;

/** ADR-0040: finishes adding an authenticator app by proving it shows the right code. */
@FunctionalInterface
public interface ConfirmTotpEnrollmentUseCase {

  /**
   * @return the recovery codes, in the form to show once; they are not recoverable afterwards
   * @throws com.clavaris.identity.application.usecase.verifytotpchallenge.InvalidTotpCodeException
   *     if the code is wrong, or no setup is waiting to be confirmed
   */
  List<String> handle(ConfirmTotpEnrollmentCommand command);
}
