package com.clavaris.identity.application.usecase.disconnectsocialidentity;

/** BR-ID-02: an Account must always keep at least one way to sign in. */
public class LastSignInMethodException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public LastSignInMethodException() {
    super("Cannot remove the last remaining sign-in method");
  }
}
