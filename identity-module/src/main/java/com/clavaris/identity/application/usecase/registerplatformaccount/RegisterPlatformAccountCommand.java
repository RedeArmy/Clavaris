package com.clavaris.identity.application.usecase.registerplatformaccount;

import com.clavaris.identity.domain.model.Email;

/**
 * @param rawPassword never logged, never persisted as-is — same BR-ID-01 discipline as {@code
 *     registeraccount.RegisterAccountCommand#rawPassword}
 */
public record RegisterPlatformAccountCommand(Email email, String rawPassword) {

  @Override
  public String toString() {
    return "RegisterPlatformAccountCommand[email=" + email + ", rawPassword=[REDACTED]]";
  }
}
