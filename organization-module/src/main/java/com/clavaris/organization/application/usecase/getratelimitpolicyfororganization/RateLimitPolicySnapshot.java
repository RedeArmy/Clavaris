package com.clavaris.organization.application.usecase.getratelimitpolicyfororganization;

import java.time.Instant;

/**
 * The dashboard's own read-only view of an Organization's effective capacity ceiling (ADR-0010
 * §6.2) — deliberately NOT the {@code RateLimitPolicy} domain object itself. When no row exists for
 * an Organization, the effective value is the system default, but there is no real, persisted
 * entity to describe: fabricating a {@code RateLimitPolicy} via {@code RateLimitPolicy#define} just
 * to satisfy this read would invent a fake {@code id}/{@code createdAt} nothing actually persisted,
 * misleading rather than clarifying what this page shows. {@code customized=false} and {@code
 * updatedAt=null} together mean "system default, never tuned for this Organization."
 *
 * <p>TD-FUT-002: this is a read-only projection on purpose — ADR-0010 §6.2 keeps tuning this value
 * operator-managed only in v1 ("tenant self-service is a v1.1 item... gated on audit logging
 * shipping first"), so this page shows the current ceiling without offering a way to change it.
 */
public record RateLimitPolicySnapshot(
    int requestsPerMinute, boolean customized, Instant updatedAt) {}
