package com.clavaris.identity.application.usecase.registerplatformaccount;

import com.clavaris.identity.domain.model.Email;

/**
 * Uniqueness is global here (unlike {@code registeraccount.EmailAlreadyRegisteredException}'s
 * per-Organization scoping) — a {@code PlatformAccount} belongs to no Organization.
 *
 * <p>SDE-III review, 2026-09-03: same BR-DATA-01 fix as the tenant-tier sibling's own identical gap
 * — see that class's own Javadoc for the full reasoning. {@code getMessage()} no longer bakes in
 * the raw email.
 */
public final class PlatformAccountEmailAlreadyRegisteredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private static final String MESSAGE = "A platform account with this email is already registered";

  public PlatformAccountEmailAlreadyRegisteredException(final Email email) {
    super(MESSAGE);
  }

  public PlatformAccountEmailAlreadyRegisteredException(final Email email, final Throwable cause) {
    super(MESSAGE, cause);
  }
}
