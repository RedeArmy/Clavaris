package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Web-layer form object for TD-FUT-025's "this wasn't me" link. Same shape and rationale as {@link
 * AuthenticateWithEmailLinkForm}'s own {@code token} field — the link itself is the complete proof,
 * nothing else for the account holder to type; {@code token} travels as a hidden field across the
 * {@code GET}→{@code POST} round trip specifically so a mail-scanner/prefetcher auto-fetching the
 * emailed {@code GET} link can never itself lock the account (see
 * ConfirmNewDeviceLoginAlertController's own Javadoc).
 */
public class ConfirmNewDeviceLoginAlertForm {

  @NotBlank(message = "Missing action link token")
  private String token;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public ConfirmNewDeviceLoginAlertForm() {
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
    return "ConfirmNewDeviceLoginAlertForm[token=[REDACTED]]";
  }
}
