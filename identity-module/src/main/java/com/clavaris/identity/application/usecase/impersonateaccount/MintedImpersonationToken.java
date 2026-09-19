package com.clavaris.identity.application.usecase.impersonateaccount;

import java.io.Serializable;
import java.time.Instant;
import java.util.Set;

/**
 * SDE-III review, 2026-09-19 — Clerk dashboard "Impersonate user" menu item. identity-module's own
 * copy of {@code app}'s {@code ImpersonationTokenIssuer.ImpersonationToken}, returned across the
 * {@link ImpersonationTokenMinter} port boundary since identity-module cannot depend on {@code app}
 * types directly (see that port's own Javadoc).
 *
 * <p>{@code Serializable}: real bug found live by this feature's own integration test — {@link
 * PlatformAccountImpersonationController} carries this record as a {@code RedirectAttributes} flash
 * attribute, which Spring Session (this deployment's Redis-backed {@code
 * RedisIndexedSessionRepository}, JDK serialization) persists to the session between the POST and
 * the redirected GET; a non-{@code Serializable} flash attribute throws {@code
 * NotSerializableException} from deep inside session-commit, turning a real successful
 * impersonation into a 500.
 */
public record MintedImpersonationToken(
    String accessToken, String tokenType, Instant expiresAt, Set<String> scope)
    implements Serializable {}
