package com.clavaris.identity.application.usecase.authenticatewithemailcode;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * @param organizationId BR-ORG-02: cross-checked against the resolved token's own account, exactly
 *     like {@code AuthenticateWithPasswordCommand}'s own organization-scoped lookup — this screen
 *     must only ever be capable of authenticating against the one Organization its own URL names.
 * @param rawIdentifier what was typed: an email or, where the Organization allows it, a username
 *     (ADR-0037)
 * @param presentedRawCode never the hash, never persisted as-is, same convention as {@code
 *     ConfirmEmailVerificationCommand}.
 */
public record AuthenticateWithEmailCodeCommand(
    OrganizationId organizationId, String rawIdentifier, String presentedRawCode) {

  /** For a caller that already holds an email address. */
  public AuthenticateWithEmailCodeCommand(
      final OrganizationId organizationId, final Email email, final String presentedRawCode) {
    this(organizationId, email.value(), presentedRawCode);
  }
}
