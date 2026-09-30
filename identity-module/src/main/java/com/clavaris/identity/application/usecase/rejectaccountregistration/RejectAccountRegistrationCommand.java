package com.clavaris.identity.application.usecase.rejectaccountregistration;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * TD-FUT-019: rejects a {@code PENDING_APPROVAL} self-registration. Same dual-caller actor
 * rationale as {@code ApproveAccountRegistrationCommand}'s own Javadoc — operator dashboard action
 * or consuming application's own backend callback, both surface as {@link
 * AuditActor#platformClient}.
 *
 * @param reason optional, {@code null}/blank legal — an operator or a consuming application's own
 *     backend may reject without giving one; never shown back to the rejected registrant by
 *     Clavaris itself (out of scope here, same as every other account-lifecycle notification).
 */
public record RejectAccountRegistrationCommand(
    AccountId accountId, String reason, AuditActor actor) {}
