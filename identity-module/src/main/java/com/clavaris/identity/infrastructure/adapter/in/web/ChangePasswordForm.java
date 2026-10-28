package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * TD-FUT-043: the self-service "change password" form: the current password plus the shared new
 * password pair. Same "HTML input plumbing, not a domain type" status as {@link
 * NewPasswordConfirmationForm}, which supplies the new password fields and their bounds.
 */
@SuppressWarnings("PMD.AtLeastOneConstructor")
public class ChangePasswordForm extends NewPasswordConfirmationForm {

  // Optional here: an Account whose password is the generated one proves itself with a recent
  // sign-in instead, and the use case rejects a blank one for an Account that chose a password.
  private String currentPassword;

  public String getCurrentPassword() {
    return currentPassword;
  }

  public void setCurrentPassword(final String currentPassword) {
    this.currentPassword = currentPassword;
  }
}
