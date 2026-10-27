package com.clavaris.organization.application.usecase.addworkspacemember;

import java.util.UUID;

/** BR-WS-04: the target email is already registered in this Organization's own account pool. */
public final class AccountAlreadyExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AccountAlreadyExistsException(final UUID organizationId, final String email) {
    super(
        "An account with email '"
            + email
            + "' is already registered in organization "
            + organizationId);
  }

  /**
   * Same message, plus the low-level identity-module exception that revealed the conflict —
   * preserves its stack trace instead of discarding it, same "never silently drop the real cause"
   * precedent {@code EmailAlreadyRegisteredException}'s own two-constructor shape already
   * establishes.
   */
  public AccountAlreadyExistsException(
      final UUID organizationId, final String email, final Throwable cause) {
    super(
        "An account with email '"
            + email
            + "' is already registered in organization "
            + organizationId,
        cause);
  }
}
