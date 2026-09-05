package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * Web-layer form object — same original rationale {@link RegisterAccountForm} used to share via
 * {@link EmailPasswordConfirmationForm}. ADR-0024 §4/§5: {@code RegisterAccountForm} no longer
 * extends that shared base (it needs policy-driven optional password/username fields this,
 * platform-tier form deliberately doesn't) — see its own Javadoc for why. This form still does; its
 * own requirements haven't changed.
 */
public class RegisterPlatformAccountForm extends EmailPasswordConfirmationForm {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public RegisterPlatformAccountForm() {
    super();
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
  }

  @Override
  public String toString() {
    return "RegisterPlatformAccountForm[email="
        + getEmail()
        + ", password=[REDACTED], confirmPassword=[REDACTED]]";
  }
}
