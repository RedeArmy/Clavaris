package com.clavaris.identity.application.usecase.requestemailsigninlink;

/** Same rationale as {@code requestemailsignincode.EmailCodeSignInNotEnabledException}. */
public final class EmailLinkSignInNotEnabledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EmailLinkSignInNotEnabledException() {
    super("Email link sign-in is not enabled for this Organization");
  }
}
