package com.clavaris.identity.application.usecase.authenticatewithidentifier;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Input to {@link AuthenticateWithIdentifierUseCase}: what the one sign-in field held — an email
 * address or a username — and the password. Raw on purpose: telling the two apart, and rejecting a
 * malformed one, is the use case's job, not the web form's.
 *
 * @param rawPassword never logged, never persisted (BR-ID-01)
 */
public record AuthenticateWithIdentifierCommand(
    OrganizationId organizationId, String rawIdentifier, String rawPassword) {

  /** Same rationale as {@code AuthenticateWithPasswordCommand#toString()} (BR-ID-01). */
  @Override
  public String toString() {
    return "AuthenticateWithIdentifierCommand[organizationId="
        + organizationId
        + ", rawPassword=[REDACTED]]";
  }
}
