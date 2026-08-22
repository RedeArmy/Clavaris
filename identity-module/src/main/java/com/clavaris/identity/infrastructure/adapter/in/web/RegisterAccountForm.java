package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * Web-layer form object — HTML form field names, Bean Validation annotations. Deliberately not the
 * same type as {@code RegisterAccountCommand} (first-vertical-slice-blueprint.md §2.4): this class
 * knows about HTML forms, the application layer's command knows about the domain, and neither
 * should know about the other's shape. Fields/validation live on {@link
 * EmailPasswordConfirmationForm} — see its own Javadoc for why sharing them with {@link
 * RegisterPlatformAccountForm} doesn't blur the platform/tenant distinction this codebase otherwise
 * insists on.
 */
public class RegisterAccountForm extends EmailPasswordConfirmationForm {

  // Written out explicitly for the same reason as Argon2PasswordHasher's own constructor (see its
  // comment) — Spring MVC/Thymeleaf need a no-arg constructor to bind form data onto.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public RegisterAccountForm() {
    super();
    // Intentionally empty — Spring MVC/Thymeleaf just need a no-arg constructor to bind
    // form-submitted values onto via the setters below; there's no state to initialise here.
  }

  /**
   * BR-ID-01: never print password/confirmPassword — same rationale as {@code
   * RegisterAccountCommand}'s overridden {@code toString()}. This class doesn't auto-generate one
   * (it's a plain class, not a record), but an explicit safe override here means a future edit
   * (e.g. adding Lombok's {@code @Data}) can't silently reintroduce the leak.
   */
  @Override
  public String toString() {
    return "RegisterAccountForm[email="
        + getEmail()
        + ", password=[REDACTED], confirmPassword=[REDACTED]]";
  }
}
