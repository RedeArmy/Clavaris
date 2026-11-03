package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Web-layer form object — same shape/rationale as {@link RequestPasswordResetForm}. */
public class RequestEmailSignInCodeForm {

  // ADR-0037: an email or, where the Organization allows it, a username. Its shape is decided by
  // the
  // use case, not here, so a bad value is answered like an unknown one and tells nothing.
  @NotBlank(message = "Email or username is required")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public RequestEmailSignInCodeForm() {
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }
}
