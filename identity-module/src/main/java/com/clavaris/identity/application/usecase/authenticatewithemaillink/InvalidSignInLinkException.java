package com.clavaris.identity.application.usecase.authenticatewithemaillink;

/** Same anti-enumeration collapsing rationale as {@code InvalidOneTimeCodeException}. */
public final class InvalidSignInLinkException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidSignInLinkException() {
    super("Invalid or expired sign-in link");
  }
}
