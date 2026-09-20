package com.clavaris.identity.application.usecase.forcepasswordresetforaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * Clerk "session tasks" parity. {@code actor} was originally always a {@link
 * AuditActor#platformClient} — the REST admin API's own {@code
 * ForcePasswordResetForAccountController} — same tier as every other {@code /api/v1/admin/**}
 * mutation (see {@code SuspendAccountCommand}'s own identical rationale). SDE-III review,
 * 2026-09-21: {@code PlatformAccountProfileAdminController} (dashboard "View Profile" > Profile tab
 * "Change password") is a second, session-authenticated caller, passing {@link
 * AuditActor#platformAccount} instead — this record never enforced the single-caller assumption,
 * only documented it.
 */
public record ForcePasswordResetForAccountCommand(AccountId accountId, AuditActor actor) {}
