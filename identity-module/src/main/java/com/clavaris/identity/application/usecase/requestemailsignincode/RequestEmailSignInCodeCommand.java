package com.clavaris.identity.application.usecase.requestemailsignincode;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Domain-shaped input — the web adapter's form maps into this, not the other way round.
 *
 * @param rawIdentifier what was typed: an email or, where the Organization allows it, a username
 *     (ADR-0037). Never trusted to name an account until the service has resolved it.
 */
public record RequestEmailSignInCodeCommand(OrganizationId organizationId, String rawIdentifier) {

  /** For a caller that already holds an email address. */
  public RequestEmailSignInCodeCommand(final OrganizationId organizationId, final Email email) {
    this(organizationId, email.value());
  }
}
