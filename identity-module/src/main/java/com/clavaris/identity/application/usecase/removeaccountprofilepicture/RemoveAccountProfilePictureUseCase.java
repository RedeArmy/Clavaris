package com.clavaris.identity.application.usecase.removeaccountprofilepicture;

/**
 * Reverts an Account to {@code GetAccountAvatarService}'s own generated-initials default avatar
 * (ADR-0026) — the self-service (or operator-driven) "remove photo" action.
 */
@FunctionalInterface
public interface RemoveAccountProfilePictureUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   */
  void handle(RemoveAccountProfilePictureCommand command);
}
