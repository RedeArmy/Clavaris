package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Web-layer form object — same shape as {@link AuthenticateWithEmailCodeForm}'s own {@code code}
 * field.
 */
public class DeviceTrustChallengeForm {

  @NotBlank(message = "Code is required")
  private String code;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public DeviceTrustChallengeForm() {
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
  }

  public String getCode() {
    return code;
  }

  public void setCode(final String code) {
    this.code = code;
  }

  /** BR-ID-01: same rationale as RegisterAccountForm's own override. */
  @Override
  public String toString() {
    return "DeviceTrustChallengeForm[code=[REDACTED]]";
  }
}
