package com.clavaris.identity.application.usecase.rejectaccountregistration;

/**
 * TD-FUT-019: rejects a {@code PENDING_APPROVAL} self-registration — transitions {@code
 * Account.status} to {@code REJECTED}. A no-op (not an error) when the Account is already past
 * {@code PENDING_APPROVAL} — same idempotent-transition shape {@code
 * ApproveAccountRegistrationUseCase} already establishes, see {@code
 * Account#rejectRegistration(String)}'s own Javadoc.
 */
@FunctionalInterface
public interface RejectAccountRegistrationUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   */
  void handle(RejectAccountRegistrationCommand command);
}
