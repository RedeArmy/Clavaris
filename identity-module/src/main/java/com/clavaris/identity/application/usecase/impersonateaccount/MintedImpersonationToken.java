package com.clavaris.identity.application.usecase.impersonateaccount;

import java.time.Instant;
import java.util.Set;

/**
 * SDE-III review, 2026-09-19 — Clerk dashboard "Impersonate user" menu item. identity-module's own
 * copy of {@code app}'s {@code ImpersonationTokenIssuer.ImpersonationToken}, returned across the
 * {@link ImpersonationTokenMinter} port boundary since identity-module cannot depend on {@code app}
 * types directly (see that port's own Javadoc).
 */
public record MintedImpersonationToken(
    String accessToken, String tokenType, Instant expiresAt, Set<String> scope) {}
