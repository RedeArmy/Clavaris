package com.clavaris.identity.application.usecase.authenticatewithpassword;

/**
 * ADR-0024 §2: thrown when the Organization's own {@code emailVerificationRequiredAtSignIn} policy
 * is on and this account's email is not yet verified — deliberately a distinct exception from
 * {@link InvalidCredentialsException}, thrown only after the presented password has already been
 * confirmed correct (never before, so a wrong password still always yields the generic
 * anti-enumeration rejection). This does mean a caller can distinguish "wrong password" from "right
 * password, unverified email" — the same trade-off every real-world IdP with this feature accepts,
 * since the feature is unusable otherwise (the account holder has no way to learn they need to
 * check their email) — accepted deliberately, not a silent gap (see BR-ID-16).
 */
public final class EmailNotVerifiedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EmailNotVerifiedException() {
    super("Email address must be verified before signing in");
  }
}
