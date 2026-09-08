package com.clavaris.identity.application.usecase.suspendplatformaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.PlatformAccountId;

/**
 * TD-FUT-031: platform-tier mirror of {@code suspendaccount.SuspendAccountCommand}. Unlike that
 * command — which has two legitimate actor tiers ({@link AuditActor#platformClient} for the admin
 * API, {@link AuditActor#account} for a tenant account holder's own "this wasn't me" link) — this
 * one has exactly one: {@link AuditActor#platformAccount}, the platform account holder themselves,
 * acting through {@code
 * confirmnewplatformdeviceloginalert.ConfirmNewPlatformDeviceLoginAlertService}'s own "this wasn't
 * me" link. There is no admin-API caller here (yet) — no operator tier above {@code
 * PlatformAccount} exists in this codebase to act as one.
 */
public record SuspendPlatformAccountCommand(
    PlatformAccountId platformAccountId, AuditActor actor) {}
