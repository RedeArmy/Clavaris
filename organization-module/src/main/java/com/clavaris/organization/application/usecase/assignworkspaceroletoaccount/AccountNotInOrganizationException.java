package com.clavaris.organization.application.usecase.assignworkspaceroletoaccount;

import java.util.UUID;

/**
 * Thrown when the submitted {@code accountId} isn't one of {@link
 * OrganizationAccountDirectory#listAccountsForOrganization}'s own results for the target
 * Workspace's Organization — same anti-cross-tenant posture {@code
 * WorkspaceRoleNotFoundException}'s own Javadoc documents for a role from a different Organization:
 * this codebase's only way to know which accounts genuinely belong to an Organization
 * (identity-module owns {@code Account}, no Maven dependency either way) is re-resolving from that
 * authoritative, org-scoped source before trusting a submitted id, never trusting a bare UUID a
 * caller could tamper with.
 */
public final class AccountNotInOrganizationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AccountNotInOrganizationException(final UUID accountId, final UUID organizationId) {
    super("Account " + accountId + " does not belong to Organization " + organizationId);
  }
}
