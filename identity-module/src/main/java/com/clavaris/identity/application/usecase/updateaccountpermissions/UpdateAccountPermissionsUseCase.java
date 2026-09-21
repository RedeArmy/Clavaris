package com.clavaris.identity.application.usecase.updateaccountpermissions;

/** Clerk "User permissions" parity — sets both operator-controlled Account permission flags. */
@FunctionalInterface
public interface UpdateAccountPermissionsUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   */
  void handle(UpdateAccountPermissionsCommand command);
}
