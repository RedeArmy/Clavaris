package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Shared email+password+confirmPassword fields for a registration-shaped web form — reused by
 * {@link RegisterAccountForm} and {@link RegisterPlatformAccountForm}, which were byte-for-byte
 * identical apart from their class name (flagged as duplicated code). Same "this is HTML input
 * plumbing, not a domain type" rationale as {@link EmailPasswordForm} — see its own Javadoc, same
 * PMD.AbstractClassWithoutAbstractMethod suppression rationale too.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.AbstractClassWithoutAbstractMethod"})
public abstract class EmailPasswordConfirmationForm {

  // Max mirrors domain.model.Email.MAX_LENGTH (254, RFC 5321 §4.5.3.1.3's actual protocol limit)
  // for the same fast client/server round trip reason as password's bounds below — the
  // authoritative check still lives in the Email value object's own constructor.
  @NotBlank(message = "Email is required")
  @Email(message = "Enter a valid email address")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  // Deliberately not enforcing the full PasswordPolicy here (bounds duplicated for a fast
  // client/server round trip on the obviously-invalid case) — the authoritative check runs again
  // inside the use case regardless of what this annotation lets through, so the policy is never
  // defined in two places that could drift. Max mirrors PasswordPolicy.MAX_LENGTH (128) for the
  // same DoS-defence reason documented there.
  @NotBlank(message = "Password is required")
  @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
  private String password;

  // Confirmation-only — never reaches a Command or anything past the controller's own mismatch
  // check (BR-ID-01: minimise how far even a correctly-typed raw password travels before it's
  // hashed or discarded).
  @NotBlank(message = "Please confirm your password")
  private String confirmPassword;

  protected EmailPasswordConfirmationForm() {
    // Intentionally empty — only ever invoked via a concrete subclass's super().
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(final String password) {
    this.password = password;
  }

  public String getConfirmPassword() {
    return confirmPassword;
  }

  public void setConfirmPassword(final String confirmPassword) {
    this.confirmPassword = confirmPassword;
  }
}
