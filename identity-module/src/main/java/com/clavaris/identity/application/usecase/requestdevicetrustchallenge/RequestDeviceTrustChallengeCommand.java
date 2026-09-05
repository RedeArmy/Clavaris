package com.clavaris.identity.application.usecase.requestdevicetrustchallenge;

import com.clavaris.identity.domain.model.AccountId;

/**
 * ADR-0024 §6: unlike the passwordless sign-in request commands (§3), this is issued for an
 * already-fully-authenticated account (the primary factor already succeeded) — no email/
 * organization needed, no anti-enumeration concern, since the caller already knows exactly which
 * account this is.
 */
public record RequestDeviceTrustChallengeCommand(AccountId accountId) {}
