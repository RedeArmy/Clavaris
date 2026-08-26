package com.clavaris.identity.application.usecase.deleteaccount;

/** BR-DATA-02/03: hard-deletes an {@code Account} and everything only it owns. */
@FunctionalInterface
public interface DeleteAccountUseCase {

  void handle(DeleteAccountCommand command);
}
