package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** TD-FUT-043: the self-service "change email" form — HTML input plumbing, not a domain type. */
@SuppressWarnings("PMD.AtLeastOneConstructor")
public class ChangeEmailForm {

  @NotBlank(message = "Email is required")
  @Email(message = "Enter a valid email address")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String newEmail;

  // Optional here: an Account with no password proves itself with a recent sign-in instead, and
  // the use case rejects a blank one for an Account that has a password.
  private String currentPassword;

  public String getNewEmail() {
    return newEmail;
  }

  public void setNewEmail(final String newEmail) {
    this.newEmail = newEmail;
  }

  public String getCurrentPassword() {
    return currentPassword;
  }

  public void setCurrentPassword(final String currentPassword) {
    this.currentPassword = currentPassword;
  }
}
