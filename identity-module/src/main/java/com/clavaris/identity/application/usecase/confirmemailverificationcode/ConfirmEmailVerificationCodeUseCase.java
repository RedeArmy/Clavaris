package com.clavaris.identity.application.usecase.confirmemailverificationcode;

import com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException;

/**
 * Inbound port — confirming an email address with the one-time code that was mailed (ADR-0024 §2).
 */
// ImplicitFunctionalInterface: one use case, one method, like every inbound port here.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface ConfirmEmailVerificationCodeUseCase {

  /**
   * @throws InvalidVerificationCodeException for an unknown address, an unknown, expired or used
   *     code, or a code issued to someone else; deliberately one type, so the answer never says
   *     which of those it was
   * @throws CodeTemporarilyLockedException after too many wrong codes in a row for the address
   *     (ADR-0033)
   */
  void handle(ConfirmEmailVerificationCodeCommand command);
}
