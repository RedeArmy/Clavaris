package com.clavaris.identity.application.usecase.registerplatformaccount;

/**
 * Uniqueness is global here (unlike {@code registeraccount.EmailAlreadyRegisteredException}'s
 * per-Organization scoping) — a {@code PlatformAccount} belongs to no Organization.
 *
 * <p>SDE-III review, 2026-09-03: same BR-DATA-01 fix as the tenant-tier sibling's own identical gap
 * — see that class's own Javadoc for the full reasoning. {@code getMessage()} no longer bakes in
 * the raw email, and {@code email} is no longer an unused constructor parameter (a static-analysis
 * finding, correctly) — dropped from the signature entirely, same follow-up as the tenant-tier fix.
 */
public final class PlatformAccountEmailAlreadyRegisteredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private static final String MESSAGE = "A platform account with this email is already registered";

  public PlatformAccountEmailAlreadyRegisteredException() {
    super(MESSAGE);
  }

  public PlatformAccountEmailAlreadyRegisteredException(final Throwable cause) {
    super(MESSAGE, cause);
  }
}
