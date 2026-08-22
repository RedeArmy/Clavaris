package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * Web-layer form object — mirrors {@link RegisterAccountForm}, same rationale throughout.
 * Fields/validation live on {@link EmailPasswordConfirmationForm}, shared with {@code
 * RegisterAccountForm} — see that class's own Javadoc for why.
 */
public class RegisterPlatformAccountForm extends EmailPasswordConfirmationForm {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public RegisterPlatformAccountForm() {
    super();
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
  }

  @Override
  public String toString() {
    return "RegisterPlatformAccountForm[email="
        + getEmail()
        + ", password=[REDACTED], confirmPassword=[REDACTED]]";
  }
}
