package com.clavaris.identity.application.usecase.chooseaccountusername;

import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;

/** ADR-0031: gives an Account without a username the one it chose. */
@FunctionalInterface
public interface ChooseAccountUsernameUseCase {

  /**
   * @throws IllegalArgumentException if the username is not 3-32 letters, digits, underscore or
   *     hyphen
   * @throws UsernameAlreadyRegisteredException if another Account of the same Organization has it
   * @throws com.clavaris.identity.application.usecase.updateaccountprofile.AccountNotFoundException
   *     if the Account does not exist
   */
  void handle(ChooseAccountUsernameCommand command);
}
