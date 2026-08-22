package com.clavaris.identity.application.usecase.registerplatformaccount;

import com.clavaris.identity.domain.model.Email;

/**
 * Uniqueness is global here (unlike {@code registeraccount.EmailAlreadyRegisteredException}'s
 * per-Organization scoping) — a {@code PlatformAccount} belongs to no Organization.
 */
public final class PlatformAccountEmailAlreadyRegisteredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PlatformAccountEmailAlreadyRegisteredException(final Email email) {
    super("A platform account with email '" + email.value() + "' is already registered");
  }

  public PlatformAccountEmailAlreadyRegisteredException(final Email email, final Throwable cause) {
    super("A platform account with email '" + email.value() + "' is already registered", cause);
  }
}
