package com.clavaris.identity.application.usecase.updateaccountpermissions;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * Input to {@link UpdateAccountPermissionsUseCase} — Clerk "User permissions" parity (ADR-0026,
 * dashboard "View Profile" > Settings tab). One combined submit for both flags, matching the
 * settings-form shape the Settings tab's own checkbox pair naturally has — not two separate one-off
 * actions the way {@code BanAccountUseCase}/{@code SuspendAccountUseCase} are, since these are
 * persistent configuration, not a state-transition event.
 */
// PMD.LongVariable: canDeleteOwnAccount/bypassesDeviceTrust name exactly what they are — mirrors
// Account's own identical field names, same convention every other descriptively-named field in
// this codebase follows.
@SuppressWarnings("PMD.LongVariable")
public record UpdateAccountPermissionsCommand(
    AccountId accountId,
    boolean canDeleteOwnAccount,
    boolean bypassesDeviceTrust,
    AuditActor actor) {}
