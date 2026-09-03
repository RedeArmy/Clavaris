package com.clavaris.identity.application.usecase.revokeplatformaccountsession;

import com.clavaris.identity.domain.model.PlatformAccountId;

/**
 * @param platformAccountId always the caller's own resolved session principal — never client input.
 * @param sessionId the raw {@code HttpSession} id the caller wants revoked, taken from a submitted
 *     form field — client input, not trusted until {@link RevokePlatformAccountSessionService}
 *     checks it.
 */
public record RevokePlatformAccountSessionCommand(
    PlatformAccountId platformAccountId, String sessionId) {}
