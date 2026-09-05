package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Web-layer form object. {@code token} is bound here (a hidden field, populated from the link's
 * query parameter on the {@code GET}) rather than read fresh from the request on {@code POST} —
 * same reasoning Thymeleaf form round-tripping already requires for every other field on this form:
 * what the user submits is exactly what was rendered, including a resubmission after a validation
 * error re-renders the same page. {@code newPassword}/{@code confirmPassword} live on {@link
 * NewPasswordConfirmationForm} — see its own Javadoc for why sharing them with {@link
 * SessionTaskPasswordResetForm} doesn't blur the two forms' otherwise-different shape.
 */
public class ConfirmPasswordResetForm extends NewPasswordConfirmationForm {

  @NotBlank(message = "Missing reset token")
  private String token;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public ConfirmPasswordResetForm() {
    super();
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
  }

  public String getToken() {
    return token;
  }

  public void setToken(final String token) {
    this.token = token;
  }

  /** BR-ID-01: same rationale as RegisterAccountForm's own override. */
  @Override
  public String toString() {
    return "ConfirmPasswordResetForm[token=[REDACTED], newPassword=[REDACTED],"
        + " confirmPassword=[REDACTED]]";
  }
}
