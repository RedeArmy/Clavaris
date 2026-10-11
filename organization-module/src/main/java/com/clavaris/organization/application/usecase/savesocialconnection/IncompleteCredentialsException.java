package com.clavaris.organization.application.usecase.savesocialconnection;

/**
 * A first-time credential needs both halves: a client id with no secret (or the other way round)
 * and nothing saved to take the missing one from cannot work.
 */
public final class IncompleteCredentialsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public IncompleteCredentialsException() {
    super("Both the client id and the client secret are required");
  }
}
