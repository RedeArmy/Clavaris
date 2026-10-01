package com.clavaris.identity.infrastructure.adapter.out.breachcheck;

/**
 * Internal to this package — {@link PwnedPasswordsBreachedPasswordChecker} is the only catcher,
 * translating every instance into BR-ID-07's own confirmed fail-open outcome ("not breached").
 * Never propagates past this package.
 */
class PwnedPasswordsLookupException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /* package */ PwnedPasswordsLookupException(final String message) {
    super(message);
  }

  /* package */ PwnedPasswordsLookupException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
