package com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount;

import java.time.Instant;

/**
 * TD-FUT-026 (closed 2026-09-02): platform-tier mirror of {@code listactivesessionsforaccount.
 * ActiveAccountSession} — one live, hosted-login {@code HttpSession} for a {@code PlatformAccount}.
 * See that record's own Javadoc for the full rationale, unchanged here beyond the tier.
 *
 * @param sessionId the raw {@code HttpSession} id — opaque to this module, only ever round-tripped
 *     back into {@link PlatformAccountActiveSessionsRepository#revoke}
 */
public record ActivePlatformAccountSession(
    String sessionId,
    String userAgent,
    String sourceIp,
    Instant createdAt,
    Instant lastAccessedAt) {}
