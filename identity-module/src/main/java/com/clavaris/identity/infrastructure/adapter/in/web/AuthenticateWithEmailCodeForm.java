package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object. {@code email} travels as a hidden field across the request→confirm round
 * trip, same reasoning {@link ConfirmPasswordResetForm}'s own {@code token} field documents.
 */
@SuppressWarnings("PMD.DataClass")
public class AuthenticateWithEmailCodeForm {

  @NotBlank(message = "Email is required")
  @Email(message = "Enter a valid email address")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  @NotBlank(message = "Code is required")
  private String code;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public AuthenticateWithEmailCodeForm() {
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public String getCode() {
    return code;
  }

  public void setCode(final String code) {
    this.code = code;
  }

  /** BR-ID-01: same rationale as RegisterAccountForm's own override. */
  @Override
  public String toString() {
    return "AuthenticateWithEmailCodeForm[email=" + email + ", code=[REDACTED]]";
  }
}
