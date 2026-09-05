package com.clavaris.identity.application.usecase.registeraccount;

/**
 * ADR-0024 §4: thrown when the Organization's own {@code usernameRequired} policy is on and no
 * username was submitted.
 */
public final class UsernameRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UsernameRequiredException() {
    super("A username is required to register for this Organization");
  }
}
