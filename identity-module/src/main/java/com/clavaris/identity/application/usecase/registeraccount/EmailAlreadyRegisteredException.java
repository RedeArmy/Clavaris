package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * BR-ORG-01: uniqueness is scoped to {@code (organizationId, email)}, never global — the same email
 * in a different Organization is not a conflict, so this exception always carries both.
 */
public final class EmailAlreadyRegisteredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EmailAlreadyRegisteredException(final OrganizationId organizationId, final Email email) {
    super(
        "An account with email '"
            + email.value()
            + "' is already registered in organization "
            + organizationId.value());
  }

  /**
   * Same message, plus the low-level exception that revealed the conflict (a lost race against the
   * unique constraint, {@link RegisterAccountService}) — preserves its stack trace instead of
   * discarding it, so a production investigation of "why did this registration fail" isn't left
   * without the actual JDBC-level cause.
   */
  public EmailAlreadyRegisteredException(
      final OrganizationId organizationId, final Email email, final Throwable cause) {
    super(
        "An account with email '"
            + email.value()
            + "' is already registered in organization "
            + organizationId.value(),
        cause);
  }
}
