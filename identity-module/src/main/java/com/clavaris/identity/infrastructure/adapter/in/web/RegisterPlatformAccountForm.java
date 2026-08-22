package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Web-layer form object — mirrors {@link RegisterAccountForm}, same rationale throughout. */
@SuppressWarnings("PMD.DataClass")
public class RegisterPlatformAccountForm {

  @NotBlank(message = "Email is required")
  @Email(message = "Enter a valid email address")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  @NotBlank(message = "Password is required")
  @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
  private String password;

  @NotBlank(message = "Please confirm your password")
  private String confirmPassword;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public RegisterPlatformAccountForm() {
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
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

  public String getConfirmPassword() {
    return confirmPassword;
  }

  public void setConfirmPassword(final String confirmPassword) {
    this.confirmPassword = confirmPassword;
  }

  @Override
  public String toString() {
    return "RegisterPlatformAccountForm[email="
        + email
        + ", password=[REDACTED], confirmPassword=[REDACTED]]";
  }
}
