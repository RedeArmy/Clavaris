package com.clavaris.identity.application.usecase.suspendaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * Reversible ban — always a {@link AuditActor#platformClient} actor, same tier as every other
 * {@code /api/v1/admin/**} mutation (see {@code DeleteAccountCommand}'s own identical rationale).
 */
public record SuspendAccountCommand(AccountId accountId, AuditActor actor) {}
