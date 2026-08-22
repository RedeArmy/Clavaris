package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object. {@code token} is bound here (a hidden field, populated from the link's
 * query parameter on the {@code GET}) rather than read fresh from the request on {@code POST} —
 * same reasoning Thymeleaf form round-tripping already requires for every other field on this form:
 * what the user submits is exactly what was rendered, including a resubmission after a validation
 * error re-renders the same page.
 */
@SuppressWarnings("PMD.DataClass")
public class ConfirmPasswordResetForm {

  @NotBlank(message = "Missing reset token")
  private String token;

  @NotBlank(message = "Password is required")
  @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
  private String newPassword;

  @NotBlank(message = "Please confirm your password")
  private String confirmPassword;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public ConfirmPasswordResetForm() {
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
  }

  public String getToken() {
    return token;
  }

  public void setToken(final String token) {
    this.token = token;
  }

  public String getNewPassword() {
    return newPassword;
  }

  public void setNewPassword(final String newPassword) {
    this.newPassword = newPassword;
  }

  public String getConfirmPassword() {
    return confirmPassword;
  }

  public void setConfirmPassword(final String confirmPassword) {
    this.confirmPassword = confirmPassword;
  }

  /** BR-ID-01: same rationale as RegisterAccountForm's own override. */
  @Override
  public String toString() {
    return "ConfirmPasswordResetForm[token=[REDACTED], newPassword=[REDACTED],"
        + " confirmPassword=[REDACTED]]";
  }
}
