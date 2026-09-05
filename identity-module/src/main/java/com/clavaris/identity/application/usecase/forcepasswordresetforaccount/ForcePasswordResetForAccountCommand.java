package com.clavaris.identity.application.usecase.forcepasswordresetforaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * Clerk "session tasks" parity: always a {@link AuditActor#platformClient} actor, same tier as
 * every other {@code /api/v1/admin/**} mutation (see {@code SuspendAccountCommand}'s own identical
 * rationale).
 */
public record ForcePasswordResetForAccountCommand(AccountId accountId, AuditActor actor) {}
