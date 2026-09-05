package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Shared email+password+confirmPassword fields for a registration-shaped web form — reused by
 * {@link RegisterAccountForm} and {@link RegisterPlatformAccountForm}. Password/confirmPassword's
 * {@code @NotBlank} lives behind the {@link PasswordRequired} validation group (see its own
 * Javadoc) rather than being duplicated as two near-identical field sets: {@code
 * RegisterPlatformAccountForm} always needs it, {@code RegisterAccountForm} only conditionally
 * (ADR-0024 §5, a runtime policy decision no static group can express) — this is what lets both
 * still share one field declaration instead of forking. Same "this is HTML input plumbing, not a
 * domain type" rationale as {@link EmailPasswordForm} — see its own Javadoc, same
 * PMD.AbstractClassWithoutAbstractMethod suppression rationale too.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.AbstractClassWithoutAbstractMethod"})
public abstract class EmailPasswordConfirmationForm {

  // Max mirrors domain.model.Email.MAX_LENGTH (254, RFC 5321 §4.5.3.1.3's actual protocol limit)
  // for the same fast client/server round trip reason as password's bounds below — the
  // authoritative check still lives in the Email value object's own constructor. Always required,
  // both tiers — no group needed, implicitly Default.
  @NotBlank(message = "Email is required")
  @Email(message = "Enter a valid email address")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  // @Size stays in the Default group (applies whenever a value IS submitted, either tier) —
  // deliberately not enforcing the full PasswordPolicy here (bounds duplicated for a fast
  // client/server round trip on the obviously-invalid case), the authoritative check runs again
  // inside the use case regardless of what this annotation lets through. Max mirrors
  // PasswordPolicy.MAX_LENGTH (128) for the same DoS-defence reason documented there. @NotBlank is
  // PasswordRequired-only — see that interface's own Javadoc for which caller opts in and why.
  @NotBlank(message = "Password is required", groups = PasswordRequired.class)
  @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
  private String password;

  // Confirmation-only — never reaches a Command or anything past the controller's own mismatch
  // check (BR-ID-01: minimise how far even a correctly-typed raw password travels before it's
  // hashed or discarded). Same PasswordRequired-only rationale as password's own @NotBlank above.
  @NotBlank(message = "Please confirm your password", groups = PasswordRequired.class)
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
