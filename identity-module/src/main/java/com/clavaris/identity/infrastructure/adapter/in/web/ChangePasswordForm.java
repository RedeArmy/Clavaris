package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/**
 * TD-FUT-043: the self-service "change password" form: the current password plus the shared new
 * password pair. Same "HTML input plumbing, not a domain type" status as {@link
 * NewPasswordConfirmationForm}, which supplies the new password fields and their bounds.
 */
@SuppressWarnings("PMD.AtLeastOneConstructor")
public class ChangePasswordForm extends NewPasswordConfirmationForm {

  @NotBlank(message = "Please enter your current password")
  private String currentPassword;

  public String getCurrentPassword() {
    return currentPassword;
  }

  public void setCurrentPassword(final String currentPassword) {
    this.currentPassword = currentPassword;
  }
}
