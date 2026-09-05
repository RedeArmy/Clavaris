package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object for {@link SessionTaskChallengeController}. No {@code token} field, unlike
 * {@code ConfirmPasswordResetForm}: the account being reset is identified by the pending session
 * state {@link SessionTaskGate} already established, never by anything the browser submits.
 */
@SuppressWarnings("PMD.DataClass")
public class SessionTaskPasswordResetForm {

  @NotBlank(message = "Password is required")
  @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
  private String newPassword;

  @NotBlank(message = "Please confirm your password")
  private String confirmPassword;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public SessionTaskPasswordResetForm() {
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
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

  /** BR-ID-01: same rationale as ConfirmPasswordResetForm's own override. */
  @Override
  public String toString() {
    return "SessionTaskPasswordResetForm[newPassword=[REDACTED], confirmPassword=[REDACTED]]";
  }
}
