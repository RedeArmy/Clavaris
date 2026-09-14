package com.clavaris.organization.application.usecase.getratelimitpolicyfororganization;

import java.time.Instant;

/**
 * The dashboard's own read view of an Organization's effective capacity ceiling (ADR-0010 §6.2) —
 * deliberately NOT the {@code RateLimitPolicy} domain object itself. When no row exists for an
 * Organization, the effective value is the system default, but there is no real, persisted entity
 * to describe: fabricating a {@code RateLimitPolicy} via {@code RateLimitPolicy#define} just to
 * satisfy this read would invent a fake {@code id}/{@code createdAt} nothing actually persisted,
 * misleading rather than clarifying what this page shows. {@code customized=false} and {@code
 * updatedAt=null} together mean "system default, never tuned for this Organization."
 *
 * <p>TD-FUT-002 (self-service tuning, shipped): {@code hardCapRequestsPerMinute} is new as of this
 * revision — the same {@code clavaris.rate-limit.capacity.hard-cap-requests-per-minute} value
 * {@code SetRateLimitPolicyForOrganizationService} already enforces, surfaced here so the
 * dashboard's own tuning form can tell an Organization owner the real ceiling they're bounded by
 * before they submit a value the service would otherwise reject, rather than making them guess or
 * discover it only from a failed submission. Still a read model, not a write capability of its own
 * — the actual write goes through {@code SetRateLimitPolicyForOrganizationUseCase}, the same one
 * the REST admin API already used, now also called by {@code PlatformRateLimitPolicyController}
 * with a {@code platformAccount} actor.
 */
@SuppressWarnings("PMD.LongVariable") // hardCapRequestsPerMinute matches its own config key's
// name, not arbitrarily long — same precedent SetRateLimitPolicyForOrganizationService's own
// identical parameter already establishes.
public record RateLimitPolicySnapshot(
    int requestsPerMinute, boolean customized, Instant updatedAt, int hardCapRequestsPerMinute) {}
