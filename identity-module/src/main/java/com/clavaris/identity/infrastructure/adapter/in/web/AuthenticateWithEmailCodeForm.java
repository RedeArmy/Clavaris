package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object. {@code email} travels as a hidden field across the request→confirm round
 * trip, same reasoning {@link ConfirmPasswordResetForm}'s own {@code token} field documents.
 */
public class AuthenticateWithEmailCodeForm {

  // ADR-0037: an email or, where the Organization allows it, a username. Its shape is decided by
  // the
  // use case, not here, so a bad value is answered like an unknown one and tells nothing.
  @NotBlank(message = "Email or username is required")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  @NotBlank(message = "Code is required")
  @Size(max = 32, message = "Code is too long")
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
