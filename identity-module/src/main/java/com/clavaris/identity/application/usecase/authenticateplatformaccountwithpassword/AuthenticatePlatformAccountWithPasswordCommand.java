package com.clavaris.identity.application.usecase.authenticateplatformaccountwithpassword;

import com.clavaris.identity.domain.model.Email;

public record AuthenticatePlatformAccountWithPasswordCommand(Email email, String rawPassword) {

  @Override
  public String toString() {
    return "AuthenticatePlatformAccountWithPasswordCommand[email="
        + email
        + ", rawPassword=[REDACTED]]";
  }
}
