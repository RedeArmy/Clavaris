package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object for {@link UsernameSignInController} — deliberately not sharing {@link
 * EmailPasswordForm}: a username has no {@code @Email} shape to validate, and {@code Username}'s
 * own domain constructor (invoked by the controller) is what actually enforces its shape, same
 * "form validates presence/size only, the domain value object validates the real rule" split {@code
 * RequestEmailSignInCodeForm} already establishes for its own field.
 */
// A plain HTML-form data holder by design — same rationale RegisterAccountForm's own identical
// suppression already documents.
@SuppressWarnings("PMD.DataClass")
public class UsernamePasswordForm {

  @NotBlank(message = "Username is required")
  @Size(max = 32, message = "Username must be at most 32 characters")
  private String username;

  @NotBlank(message = "Password is required")
  private String password;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public UsernamePasswordForm() {
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(final String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(final String password) {
    this.password = password;
  }

  /** BR-ID-01: same rationale as LoginForm's own override. */
  @Override
  public String toString() {
    return "UsernamePasswordForm[username=" + username + ", password=[REDACTED]]";
  }
}
