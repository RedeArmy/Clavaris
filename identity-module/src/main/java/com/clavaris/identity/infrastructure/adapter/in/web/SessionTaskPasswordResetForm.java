package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * Web-layer form object for {@link SessionTaskChallengeController}. No {@code token} field, unlike
 * {@code ConfirmPasswordResetForm}: the account being reset is identified by the pending session
 * state {@link SessionTaskGate} already established, never by anything the browser submits.
 */
public class SessionTaskPasswordResetForm extends NewPasswordConfirmationForm {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public SessionTaskPasswordResetForm() {
    super();
    // Intentionally empty — Spring MVC/Thymeleaf just need a no-arg constructor to bind
    // form-submitted values onto via the inherited setters; there's no state to initialise here.
  }

  /** BR-ID-01: same rationale as ConfirmPasswordResetForm's own override. */
  @Override
  public String toString() {
    return "SessionTaskPasswordResetForm[newPassword=[REDACTED], confirmPassword=[REDACTED]]";
  }
}
