package com.clavaris.identity.application.usecase.banaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * SDE-III review, 2026-09-19 — Clerk dashboard "Users" parity: a deliberately separate use case
 * from {@code suspendaccount.SuspendAccountUseCase}, not a reuse of it — see {@code
 * AccountStatus}'s own Javadoc for why the two states/actions are kept distinct.
 */
public record BanAccountCommand(AccountId accountId, AuditActor actor) {}
