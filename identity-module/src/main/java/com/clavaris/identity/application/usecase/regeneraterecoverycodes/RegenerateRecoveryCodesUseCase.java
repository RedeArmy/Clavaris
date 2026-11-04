package com.clavaris.identity.application.usecase.regeneraterecoverycodes;

import java.util.List;

/** ADR-0040: replaces the recovery codes with a new set. */
@FunctionalInterface
public interface RegenerateRecoveryCodesUseCase {

  /**
   * @return the new codes, to show once; the old ones stop working
   * @throws com.clavaris.identity.application.usecase.verifytotpchallenge.InvalidTotpCodeException
   *     if the code is wrong
   * @throws com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException if
   *     the method is paused
   */
  List<String> handle(RegenerateRecoveryCodesCommand command);
}
