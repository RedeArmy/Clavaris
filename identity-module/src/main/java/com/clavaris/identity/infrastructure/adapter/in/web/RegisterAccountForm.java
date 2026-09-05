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
 * <p>ADR-0024 §4/§5: no longer shares {@link EmailPasswordConfirmationForm} with {@link
 * RegisterPlatformAccountForm} — a deliberate divergence, not a reversal of that class's own
 * "shared HTML plumbing" rationale: the platform tier has no username or password-optional policy
 * to accommodate, so forcing this form to keep sharing it would mean either weakening the platform
 * form's own {@code @NotBlank} password guarantee or duplicating validation logic anyway. {@code
 * password}/{@code confirmPassword} are deliberately NOT {@code @NotBlank} here — whether they're
 * required depends on the Organization's own {@code passwordAtSignUpEnabled} policy, a business
 * rule the annotation layer can't express; {@link RegisterAccountController} enforces it with the
 * same {@code bindingResult.rejectValue(...)} pattern it already uses for the password-confirmation
 * mismatch check.
 */
@SuppressWarnings("PMD.DataClass")
public class RegisterAccountForm {

  @NotBlank(message = "Email is required")
  @Email(message = "Enter a valid email address")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  // Presence is policy-driven (see this class's own Javadoc), so deliberately no @NotBlank — but
  // @Size still applies whenever a value IS submitted (JSR 380: @Size treats null as valid,
  // deferring null-handling to @NotNull/@NotBlank, which is exactly the split this field needs).
  // Bounds mirror PasswordPolicy's own (8-128) for the same fast client/server round trip
  // reasoning EmailPasswordConfirmationForm's own identical field already documents.
  @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
  private String password;

  private String confirmPassword;

  // Presence/requiredness is also policy-driven (usernameSignUpEnabled/usernameRequired) — the
  // shape itself (length, character set) is enforced by Username's own domain constructor, not
  // duplicated here as a @Pattern the two could drift apart from.
  @Size(max = 32, message = "Username must be at most 32 characters")
  private String username;

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

  public String getUsername() {
    return username;
  }

  public void setUsername(final String username) {
    this.username = username;
  }

  /**
   * BR-ID-01: never print password/confirmPassword — same rationale as {@code
   * RegisterAccountCommand}'s overridden {@code toString()}.
   */
  @Override
  public String toString() {
    return "RegisterAccountForm[email="
        + email
        + ", username="
        + username
        + ", password=[REDACTED], confirmPassword=[REDACTED]]";
  }
}
