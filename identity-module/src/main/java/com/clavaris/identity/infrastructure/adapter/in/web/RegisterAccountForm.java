package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object — HTML form field names, Bean Validation annotations. Deliberately not the
 * same type as {@code RegisterAccountCommand} (first-vertical-slice-blueprint.md §2.4): this class
 * knows about HTML forms, the application layer's command knows about the domain, and neither
 * should know about the other's shape.
 *
 * <p>A mutable bean, not a record: Thymeleaf's {@code th:field} two-way form binding needs setters
 * — the same reason PMD's DataClass rule is suppressed below: a web-layer form is *supposed* to be
 * a plain data holder, by design, not a bag-of-fields smell.
 */
@SuppressWarnings("PMD.DataClass")
public class RegisterAccountForm {

  // Max mirrors domain.model.Email.MAX_LENGTH (254, RFC 5321 §4.5.3.1.3's actual protocol limit)
  // for the same fast client/server round trip reason as password's bounds below — the
  // authoritative check still lives in the Email value object's own constructor.
  @NotBlank(message = "Email is required")
  @Email(message = "Enter a valid email address")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  // Deliberately not enforcing the full PasswordPolicy here (bounds duplicated for a fast
  // client/server round trip on the obviously-invalid case) — the authoritative check is
  // PasswordPolicy.isSatisfiedBy, run again inside RegisterAccountService regardless of what this
  // annotation lets through, so the policy is never defined in two places that could drift. Max
  // mirrors PasswordPolicy.MAX_LENGTH (128) for the same DoS-defence reason documented there.
  @NotBlank(message = "Password is required")
  @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
  private String password;

  // Confirmation-only — never reaches RegisterAccountCommand or anything past
  // RegisterAccountController's own mismatch check (BR-ID-01: minimise how far even a
  // correctly-typed raw password travels before it's hashed or discarded).
  @NotBlank(message = "Please confirm your password")
  private String confirmPassword;

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor (see its
  // comment) — Spring MVC/Thymeleaf need a no-arg constructor to bind form data onto.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public RegisterAccountForm() {
    // Intentionally empty — Spring MVC/Thymeleaf just need a no-arg constructor to bind
    // form-submitted values onto via the setters below; there's no state to initialise here.
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

  /**
   * BR-ID-01 / CLAUDE.md §6: never print password/confirmPassword — same rationale as {@code
   * RegisterAccountCommand}'s overridden {@code toString()}. This class doesn't auto-generate one
   * (it's a plain class, not a record), but an explicit safe override here means a future edit
   * (e.g. adding Lombok's {@code @Data}) can't silently reintroduce the leak.
   */
  @Override
  public String toString() {
    return "RegisterAccountForm[email="
        + email
        + ", password=[REDACTED], confirmPassword=[REDACTED]]";
  }
}
