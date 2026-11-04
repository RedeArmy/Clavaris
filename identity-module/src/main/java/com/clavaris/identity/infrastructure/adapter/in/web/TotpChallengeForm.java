package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The one field of the authenticator-code page: a six-digit code or a recovery code. */
public class TotpChallengeForm {

  // Longest accepted entry is a recovery code with hyphens (19) or spaces; the bound only stops
  // an oversized body from being handed on.
  @NotBlank(message = "Code is required")
  @Size(max = 32, message = "Code is too long")
  private String code;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public TotpChallengeForm() {
    // Intentionally empty — see RegisterAccountForm's own identical constructor comment.
  }

  public String getCode() {
    return code;
  }

  public void setCode(final String code) {
    this.code = code;
  }

  @Override
  public String toString() {
    return "TotpChallengeForm[code=<redacted>]";
  }
}
