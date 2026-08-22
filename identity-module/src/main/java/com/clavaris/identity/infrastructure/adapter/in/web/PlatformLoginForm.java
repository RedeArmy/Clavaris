package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Web-layer form object — mirrors {@link LoginForm}. */
@SuppressWarnings("PMD.DataClass")
public class PlatformLoginForm {

  @NotBlank(message = "Email is required")
  @Email(message = "Enter a valid email address")
  private String email;

  @NotBlank(message = "Password is required")
  private String password;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public PlatformLoginForm() {
    // Intentionally empty.
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

  @Override
  public String toString() {
    return "PlatformLoginForm[email=" + email + ", password=[REDACTED]]";
  }
}
