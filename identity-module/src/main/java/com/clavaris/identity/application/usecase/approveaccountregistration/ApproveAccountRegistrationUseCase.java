package com.clavaris.identity.application.usecase.approveaccountregistration;

/**
 * TD-FUT-019: approves a {@code PENDING_APPROVAL} self-registration — transitions {@code
 * Account.status} to {@code ACTIVE}. A no-op (not an error) when the Account is already past {@code
 * PENDING_APPROVAL} — same idempotent-transition shape {@code Account.suspend()}/{@code
 * Account.ban()} already establish, see {@code Account#approveRegistration()}'s own Javadoc.
 */
@FunctionalInterface
public interface ApproveAccountRegistrationUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   */
  void handle(ApproveAccountRegistrationCommand command);
}
