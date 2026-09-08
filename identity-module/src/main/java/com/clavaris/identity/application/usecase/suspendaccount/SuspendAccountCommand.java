package com.clavaris.identity.application.usecase.suspendaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * Reversible ban. {@link SuspendAccountController} (admin API) always constructs this with a {@link
 * AuditActor#platformClient} actor, same tier as every other {@code /api/v1/admin/**} mutation (see
 * {@code DeleteAccountCommand}'s own identical rationale) — but TD-FUT-025's {@code
 * confirmnewdeviceloginalert.ConfirmNewDeviceLoginAlertService} is a second, deliberate caller with
 * an {@link AuditActor#account} actor instead: the account holder themselves, acting through their
 * own "this wasn't me" link, not a platform-tier admin action.
 */
public record SuspendAccountCommand(AccountId accountId, AuditActor actor) {}
