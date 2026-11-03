package com.clavaris.identity.application.usecase.codelockout;

/**
 * Too many wrong codes in a row: this method is paused for a while. Raised for an address with no
 * account just the same as for one with an account, so it is not an enumeration oracle; the person
 * can still sign in with their password.
 */
public final class CodeTemporarilyLockedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CodeTemporarilyLockedException() {
    super("Too many incorrect codes; this method is temporarily paused");
  }
}
