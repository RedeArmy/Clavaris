package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Shared email+password fields for a login-shaped web form — reused by {@link LoginForm} and {@link
 * PlatformLoginForm}, which were byte-for-byte identical apart from their class name (flagged as
 * duplicated code). Extracting this doesn't blur the platform/tenant distinction ADR-0012 insists
 * on elsewhere: that principle is about domain identities (never a shared {@code Account}/{@code
 * PlatformAccount} type); this is HTML input plumbing with no domain meaning beyond "an email
 * string and a password string," identical regardless of which tier logs in. Public (not
 * package-private) so Spring/Thymeleaf's reflective property binding on the concrete {@code
 * LoginForm}/{@code PlatformLoginForm} subclasses never has to reason about an inherited-from-a-
 * non-public-superclass edge case — abstract, so nothing ever binds to it directly (hence no
 * abstract method of its own — PMD.AbstractClassWithoutAbstractMethod suppressed: the point of
 * abstractness here is "never instantiate this on its own," not "force subclasses to implement
 * something").
 */
@SuppressWarnings({"PMD.DataClass", "PMD.AbstractClassWithoutAbstractMethod"})
public abstract class EmailPasswordForm {

  @NotBlank(message = "Email is required")
  @Email(message = "Enter a valid email address")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  // No @Size lower/upper bound here — same "this is a login attempt, not a registration"
  // rationale as LoginForm's own original comment on this field.
  @NotBlank(message = "Password is required")
  private String password;

  protected EmailPasswordForm() {
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
}
