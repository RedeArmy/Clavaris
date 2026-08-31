package com.clavaris.identity.application.usecase.listactivesessionsforaccount;

import java.time.Instant;

/**
 * One live, hosted-login {@code HttpSession} for a tenant {@code Account} — deliberately not the
 * domain {@code Session} aggregate (BR-ID-03's OAuth refresh-token chain); see that class's own
 * Javadoc for why the two must not be conflated. {@code userAgent}/{@code sourceIp} are whatever
 * {@code SpringSecurityAuthenticatedSessionEstablisher} captured at the moment this session was
 * established — either may be {@code null} for a session established before this feature shipped,
 * or from a client that sent no {@code User-Agent} header at all.
 *
 * @param sessionId the raw {@code HttpSession} id — opaque to this module, only ever round-tripped
 *     back into {@link AccountActiveSessionsRepository#revoke}
 */
public record ActiveAccountSession(
    String sessionId,
    String userAgent,
    String sourceIp,
    Instant createdAt,
    Instant lastAccessedAt) {}
