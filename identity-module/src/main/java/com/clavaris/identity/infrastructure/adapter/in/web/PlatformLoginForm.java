package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * Web-layer form object — mirrors {@link LoginForm}. Fields/validation live on {@link
 * EmailPasswordForm}, shared with {@code LoginForm} — see that class's own Javadoc for why.
 */
public class PlatformLoginForm extends EmailPasswordForm {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public PlatformLoginForm() {
    super();
    // Intentionally empty.
  }

  @Override
  public String toString() {
    return "PlatformLoginForm[email=" + getEmail() + ", password=[REDACTED]]";
  }
}
