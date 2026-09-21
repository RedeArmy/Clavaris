package com.clavaris.identity.application.usecase.updateaccountprofile;

/** Clerk "View Profile" > Profile tab parity — edits an Account's own first/last name. */
@FunctionalInterface
public interface UpdateAccountProfileUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   */
  void handle(UpdateAccountProfileCommand command);
}
