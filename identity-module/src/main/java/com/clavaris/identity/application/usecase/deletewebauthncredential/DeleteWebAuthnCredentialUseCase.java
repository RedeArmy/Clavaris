package com.clavaris.identity.application.usecase.deletewebauthncredential;

@FunctionalInterface
public interface DeleteWebAuthnCredentialUseCase {

  /**
   * @return {@code true} if a credential was actually deleted, {@code false} for "not found or not
   *     yours" — the caller treats either outcome as a safe no-op response, never an error.
   */
  boolean handle(DeleteWebAuthnCredentialCommand command);
}
