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
 * @param rawUsername ADR-0024 §4: {@code null}/blank when the submitter didn't provide one — legal
 *     unless the Organization's own policy requires it ({@code UsernameRequiredException}).
 * @param publicSelfRegistration TD-FUT-019: {@code true} only for a genuine public signup through
 *     {@code RegisterAccountController}'s own hosted form — the one case the Organization's own
 *     {@code AccountAuthenticationPolicy.selfRegistrationRequiresApproval()} ever applies to.
 *     {@code false} for every other real caller of this same use case (e.g. {@code
 *     WorkspaceMemberAccountProvisionerBridge}'s admin-provisioned Workspace member creation,
 *     BR-WS-04) — an operator directly creating an Account has already made the approval decision
 *     by the act of creating it; gating it a second time against an unrelated policy this
 *     population was never meant to be subject to (ADR-0024/TD-FUT-019: "separate from Workspace,
 *     and tenant-optional") would be a real correctness bug, not extra safety.
 */
// PMD.LongVariable: publicSelfRegistration names exactly what it gates — see its own Javadoc
// above for why a shorter name would leave out the one fact that actually matters here.
@SuppressWarnings("PMD.LongVariable")
public record RegisterAccountCommand(
    OrganizationId organizationId,
    Email email,
    String rawPassword,
    String rawUsername,
    boolean publicSelfRegistration) {

  /**
   * BR-ID-01 ("no PII, credential, or token value in logs, ever"): a record's auto-generated {@code
   * toString()} prints every component, {@code rawPassword} included — this override exists
   * specifically to close that gap. Anything that ever logs a command whole (an unhandled-exception
   * handler, an accidental {@code logger.debug(command)}, request tracing) would otherwise leak the
   * raw password into logs without a single line of code looking like it does.
   */
  @Override
  public String toString() {
    return "RegisterAccountCommand[organizationId="
        + organizationId
        + ", email="
        + email
        + ", rawPassword=[REDACTED], rawUsername="
        + rawUsername
        + ", publicSelfRegistration="
        + publicSelfRegistration
        + "]";
  }
}
