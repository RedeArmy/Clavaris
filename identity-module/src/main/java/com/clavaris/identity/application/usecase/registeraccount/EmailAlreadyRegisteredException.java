package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * BR-ORG-01: uniqueness is scoped to {@code (organizationId, email)}, never global — the same email
 * in a different Organization is not a conflict, so this exception always carries {@code
 * organizationId} for correlation.
 *
 * <p><b>SDE-III review, 2026-09-03 — real bug found and closed:</b> {@code getMessage()} used to
 * bake the raw email in directly, contradicting BR-DATA-01 (no PII in logs, ever) — the one
 * exception/command in this module that didn't redact it; every sibling (e.g. {@link
 * com.clavaris.identity.application.usecase.confirmpasswordreset.ConfirmPasswordResetCommand}'s own
 * {@code toString()} override) already does. A generic {@code catch (RuntimeException e) {
 * log.warn(..., e) }} anywhere in the call chain — or an APM error tracker capturing this
 * exception's own message — would have written the plaintext email to logs. Code-review follow-up
 * to that same fix: {@code email} was left as an unused constructor parameter (a static-analysis
 * finding, correctly) — dropped from the signature entirely, not just the message, since a
 * parameter this constructor does nothing with is misleading API surface on its own.
 */
public final class EmailAlreadyRegisteredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EmailAlreadyRegisteredException(final OrganizationId organizationId) {
    super(message(organizationId));
  }

  /**
   * Same message, plus the low-level exception that revealed the conflict (a lost race against the
   * unique constraint, {@link RegisterAccountService}) — preserves its stack trace instead of
   * discarding it, so a production investigation of "why did this registration fail" isn't left
   * without the actual JDBC-level cause.
   */
  public EmailAlreadyRegisteredException(
      final OrganizationId organizationId, final Throwable cause) {
    super(message(organizationId), cause);
  }

  // BR-DATA-01: never the raw email — see this class's own Javadoc.
  private static String message(final OrganizationId organizationId) {
    return "An account with this email is already registered in organization "
        + organizationId.value();
  }
}
