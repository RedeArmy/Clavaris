package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object. The field keeps the name {@code email}, as the sign-in forms do
 * (ADR-0032), because the rate limits key on it; it holds an email or, where the Organization
 * allows it, a username. Its shape is the use case's to judge, so a bad value is answered like an
 * unknown one.
 */
public class ResendEmailVerificationForm {

  @NotBlank(message = "Email or username is required")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  public ResendEmailVerificationForm() {
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }
}
