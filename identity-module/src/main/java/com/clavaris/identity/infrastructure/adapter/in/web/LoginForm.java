package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * Web-layer form object for {@link LoginController} — same shape rationale as {@link
 * RegisterAccountForm}: a mutable bean for Thymeleaf's two-way {@code th:field} binding, not the
 * same type as {@code AuthenticateWithPasswordCommand}. Fields/validation live on {@link
 * EmailPasswordForm} — see its own Javadoc for why sharing them with {@link PlatformLoginForm}
 * doesn't blur the platform/tenant distinction this codebase otherwise insists on.
 */
public class LoginForm extends EmailPasswordForm {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public LoginForm() {
    super();
    // Intentionally empty — Spring MVC/Thymeleaf just need a no-arg constructor to bind
    // form-submitted values onto via the setters below; there's no state to initialise here.
  }

  /** BR-ID-01 rationale — same as {@code RegisterAccountForm#toString()}. */
  @Override
  public String toString() {
    return "LoginForm[email=" + getEmail() + ", password=[REDACTED]]";
  }
}
