package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Shared {@code newPassword}/{@code confirmPassword} fields for a "set a new password" web form —
 * reused by {@link ConfirmPasswordResetForm} (which adds its own {@code token} field) and {@link
 * SessionTaskPasswordResetForm} (which adds nothing else — the account is identified by pending
 * session state, never a form field). Same "this is HTML input plumbing, not a domain type"
 * rationale as {@code EmailPasswordConfirmationForm} — see its own Javadoc, same
 * PMD.AbstractClassWithoutAbstractMethod suppression rationale too.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.AbstractClassWithoutAbstractMethod"})
public abstract class NewPasswordConfirmationForm {

  @NotBlank(message = "Password is required")
  @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
  private String newPassword;

  @NotBlank(message = "Please confirm your password")
  private String confirmPassword;

  protected NewPasswordConfirmationForm() {
    // Intentionally empty — only ever invoked via a concrete subclass's super().
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
}
