package com.clavaris.clientregistry.application.usecase.deleteorganizationclient;

/**
 * A Secret Key must be deactivated before it can be permanently deleted: an owner has to
 * consciously switch it off first, accepting that every consumer using it stops authenticating,
 * before this irreversible action becomes reachable. Mapped to {@code 409 Conflict} by the
 * dashboard controller, the same status {@code OAuthClientActiveException} uses.
 */
public final class OrganizationClientActiveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrganizationClientActiveException(final String clientId) {
    super(
        "OrganizationClient "
            + clientId
            + " must be deactivated before it can be permanently deleted");
  }
}
