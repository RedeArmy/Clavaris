package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object for {@link LoginController} — same shape rationale as {@link
 * RegisterAccountForm}: a mutable bean for Thymeleaf's two-way {@code th:field} binding, not the
 * same type as {@code AuthenticateWithPasswordCommand}.
 */
@SuppressWarnings("PMD.DataClass")
public class LoginForm {

  @NotBlank(message = "Email is required")
  @Email(message = "Enter a valid email address")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  // No @Size lower/upper bound here unlike RegisterAccountForm's password field — this is a login
  // attempt, not a registration; rejecting an obviously-too-short candidate before it even reaches
  // AuthenticateWithPasswordService would be a client/server round-trip nicety, not a security
  // property, so it's not worth duplicating PasswordPolicy's bounds for a field the use case
  // rejects (as InvalidCredentialsException, indistinguishably from any other reason) regardless.
  @NotBlank(message = "Password is required")
  private String password;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public LoginForm() {
    // Intentionally empty — Spring MVC/Thymeleaf just need a no-arg constructor to bind
    // form-submitted values onto via the setters below; there's no state to initialise here.
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(final String password) {
    this.password = password;
  }

  /** BR-ID-01 rationale — same as {@code RegisterAccountForm#toString()}. */
  @Override
  public String toString() {
    return "LoginForm[email=" + email + ", password=[REDACTED]]";
  }
}
