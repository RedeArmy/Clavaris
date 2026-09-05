package com.clavaris.identity.application.usecase.authenticatewithusername;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;

/** Domain-shaped input, same convention as {@code AuthenticateWithPasswordCommand}. */
public record AuthenticateWithUsernameCommand(
    OrganizationId organizationId, Username username, String rawPassword) {

  /**
   * BR-ID-01: same redaction rationale as {@code AuthenticateWithPasswordCommand}'s own override.
   */
  @Override
  public String toString() {
    return "AuthenticateWithUsernameCommand[organizationId="
        + organizationId
        + ", username="
        + username
        + ", rawPassword=[REDACTED]]";
  }
}
