package com.clavaris.identity.application.usecase.requestplatformaccountemailverification;

import com.clavaris.identity.domain.model.PlatformAccountId;

/**
 * Mirrors {@code requestemailverification.UnknownAccountException} — same "programming error, never
 * user-facing input" rationale.
 */
public final class UnknownPlatformAccountException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnknownPlatformAccountException(final PlatformAccountId platformAccountId) {
    super("No platform account found for id " + platformAccountId.value());
  }
}
