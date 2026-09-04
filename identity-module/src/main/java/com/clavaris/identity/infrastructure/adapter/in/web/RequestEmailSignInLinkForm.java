package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Web-layer form object — same shape/rationale as {@link RequestPasswordResetForm}. */
public class RequestEmailSignInLinkForm {

  @NotBlank(message = "Email is required")
  @Email(message = "Enter a valid email address")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public RequestEmailSignInLinkForm() {
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }
}
