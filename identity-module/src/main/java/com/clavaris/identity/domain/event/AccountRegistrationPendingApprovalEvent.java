package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;

/**
 * TD-FUT-019: {@code account.registration.pending_approval} — written alongside {@code
 * account.created} (never instead of it, so an existing subscriber never loses the fact a row was
 * created), only when the owning Organization's own {@code
 * AccountAuthenticationPolicy.selfRegistrationRequiresApproval()} is on. The one event a consuming
 * application's own backend needs to subscribe to (via a {@code WebhookEndpoint}) to learn a gated
 * signup is waiting on its own approve/reject decision — see {@code
 * ApproveAccountRegistrationController}/{@code RejectAccountRegistrationController}'s own Javadoc
 * for the callback endpoints this event exists to trigger.
 */
public record AccountRegistrationPendingApprovalEvent(
    AccountId accountId, OrganizationId organizationId, String email, Instant occurredAt) {

  public static AccountRegistrationPendingApprovalEvent from(final Account account) {
    return new AccountRegistrationPendingApprovalEvent(
        account.id(), account.organizationId(), account.email().value(), Instant.now());
  }
}
