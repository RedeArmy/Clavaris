package com.clavaris.identity.application.usecase.requestemailsignincode;

/**
 * ADR-0024 §3: thrown when an Organization's own {@code emailCodeSignInEnabled} policy is off —
 * unlike {@code InvalidCredentialsException}'s anti-enumeration collapsing, whether an Organization
 * *offers* a given sign-in method at all is not account-specific secret information (the login page
 * itself already reveals it by which buttons/forms it renders, same posture {@code
 * SocialLoginNotEnabledForProviderException} already establishes for social login).
 */
public final class EmailCodeSignInNotEnabledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EmailCodeSignInNotEnabledException() {
    super("Email code sign-in is not enabled for this Organization");
  }
}
