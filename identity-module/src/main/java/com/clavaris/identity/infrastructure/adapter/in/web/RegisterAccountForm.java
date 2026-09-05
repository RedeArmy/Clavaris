package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Size;

/**
 * Web-layer form object — HTML form field names, Bean Validation annotations. Deliberately not the
 * same type as {@code RegisterAccountCommand} (first-vertical-slice-blueprint.md §2.4): this class
 * knows about HTML forms, the application layer's command knows about the domain, and neither
 * should know about the other's shape.
 *
 * <p>ADR-0024 §4/§5: extends {@link EmailPasswordConfirmationForm} for email/password/
 * confirmPassword (shared, byte-for-byte, with {@link RegisterPlatformAccountForm}) and adds only
 * {@code username} here. Password/confirmPassword's requiredness is policy-driven
 * (passwordAtSignUpEnabled) — a business rule no static validation group can express — so this
 * class deliberately validates with plain {@code @Valid} (the Default group only, never {@link
 * PasswordRequired}) and {@link RegisterAccountController} enforces "required" itself with the same
 * {@code bindingResult.rejectValue(...)} pattern it already uses for the password-confirmation
 * mismatch check.
 */
public class RegisterAccountForm extends EmailPasswordConfirmationForm {

  // Presence/requiredness is also policy-driven (usernameSignUpEnabled/usernameRequired) — the
  // shape itself (length, character set) is enforced by Username's own domain constructor, not
  // duplicated here as a @Pattern the two could drift apart from.
  @Size(max = 32, message = "Username must be at most 32 characters")
  private String username;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public RegisterAccountForm() {
    super();
    // Intentionally empty — Spring MVC/Thymeleaf just need a no-arg constructor to bind
    // form-submitted values onto via the setters below; there's no state to initialise here.
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
        + getEmail()
        + ", username="
        + username
        + ", password=[REDACTED], confirmPassword=[REDACTED]]";
  }
}
