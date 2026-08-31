package com.clavaris.identity.application.usecase.revokeaccountsession;

import com.clavaris.identity.domain.model.AccountId;

/**
 * @param accountId always the caller's own resolved session principal — never client input.
 * @param sessionId the raw {@code HttpSession} id the caller wants revoked, taken from a submitted
 *     form field — client input, and therefore not trusted to actually belong to {@code accountId}
 *     until {@link RevokeAccountSessionService} checks it.
 */
public record RevokeAccountSessionCommand(AccountId accountId, String sessionId) {}
