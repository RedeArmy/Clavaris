package com.clavaris.identity.application.usecase.authenticatewithpassword;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Input to {@link AuthenticateWithPasswordUseCase} — same domain-shaped-not-a-web-DTO rationale as
 * {@code RegisterAccountCommand}.
 *
 * @param rawPassword never logged, never persisted — only ever passed to {@link PasswordVerifier},
 *     which compares it against the stored hash and discards it (BR-ID-01).
 */
public record AuthenticateWithPasswordCommand(
    OrganizationId organizationId, Email email, String rawPassword) {

  /**
   * Same rationale as {@code RegisterAccountCommand#toString()} — a record's auto-generated {@code
   * toString()} would otherwise print {@code rawPassword} verbatim (BR-ID-01, CLAUDE.md §6).
   */
  @Override
  public String toString() {
    return "AuthenticateWithPasswordCommand[organizationId="
        + organizationId
        + ", email="
        + email
        + ", rawPassword=[REDACTED]]";
  }
}
