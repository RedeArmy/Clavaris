package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Input to {@link RegisterAccountUseCase} — domain-shaped (uses {@link Email}/{@link
 * OrganizationId}, not bare strings/UUIDs), but not a web DTO: the web adapter's own form object
 * (Bean Validation annotations, HTML-form field names) maps into this, not the other way round.
 *
 * @param rawPassword never logged, never persisted as-is — hashed by {@link PasswordHasher} before
 *     it touches {@link AccountRepository} (BR-ID-01).
 */
public record RegisterAccountCommand(
    OrganizationId organizationId, Email email, String rawPassword) {

  /**
   * BR-ID-01 / CLAUDE.md §6 ("no PII, credential, or token value in logs, ever"): a record's
   * auto-generated {@code toString()} prints every component, {@code rawPassword} included — this
   * override exists specifically to close that gap. Anything that ever logs a command whole (an
   * unhandled-exception handler, an accidental {@code logger.debug(command)}, request tracing)
   * would otherwise leak the raw password into logs without a single line of code looking like it
   * does.
   */
  @Override
  public String toString() {
    return "RegisterAccountCommand[organizationId="
        + organizationId
        + ", email="
        + email
        + ", rawPassword=[REDACTED]]";
  }
}
