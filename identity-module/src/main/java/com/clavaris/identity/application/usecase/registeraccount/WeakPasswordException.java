package com.clavaris.identity.application.usecase.registeraccount;

/**
 * The submitted password doesn't satisfy {@code PasswordPolicy} — never carries the password itself
 * (BR-ID-01: never logged, never held longer than necessary).
 */
public final class WeakPasswordException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WeakPasswordException() {
    super("Password does not meet the minimum policy requirements");
  }
}
