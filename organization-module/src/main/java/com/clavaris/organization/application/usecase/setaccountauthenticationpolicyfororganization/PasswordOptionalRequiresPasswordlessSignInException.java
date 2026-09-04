package com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization;

/**
 * {@code passwordAtSignUpEnabled=false} means sign-up must complete through some other proof of
 * email control instead — the ADR-0024 §3 passwordless email flows — so at least one of {@code
 * emailCodeSignInEnabled}/{@code emailLinkSignInEnabled} must also be {@code true}, or a tenant
 * could persist a policy with no way for anyone to ever complete sign-up at all.
 */
public final class PasswordOptionalRequiresPasswordlessSignInException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PasswordOptionalRequiresPasswordlessSignInException() {
    super(
        "passwordAtSignUpEnabled=false requires emailCodeSignInEnabled or"
            + " emailLinkSignInEnabled to be true");
  }
}
