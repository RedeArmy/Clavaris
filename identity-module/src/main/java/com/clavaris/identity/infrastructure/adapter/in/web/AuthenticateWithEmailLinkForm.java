package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Web-layer form object. {@code token} travels as a hidden field across the {@code GET}→{@code
 * POST} round trip, same reasoning {@link ConfirmPasswordResetForm}'s own {@code token} field
 * documents — the only field on this form, since the link itself is the complete proof, nothing
 * else for the account holder to type.
 */
public class AuthenticateWithEmailLinkForm {

  @NotBlank(message = "Missing sign-in link token")
  private String token;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public AuthenticateWithEmailLinkForm() {
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
  }

  public String getToken() {
    return token;
  }

  public void setToken(final String token) {
    this.token = token;
  }

  /** BR-ID-01: same rationale as RegisterAccountForm's own override. */
  @Override
  public String toString() {
    return "AuthenticateWithEmailLinkForm[token=[REDACTED]]";
  }
}
