package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0010 §6.2/BR-ORG-05: the capacity layer's per-{@code Organization} aggregate ceiling — a
 * noisy-neighbor guard, never the anti-abuse defense itself (that layer, §6.1, is fixed and
 * system-wide, never stored per-tenant, so it has no domain model at all). Absence of a row for a
 * given {@code Organization} means "use the system default," not "unlimited" — {@code
 * RateLimitPolicyRepository.findByOrganizationId} returning empty is the normal, expected state for
 * every Organization that has never had its ceiling tuned by an operator (BR-ORG-06: {@code
 * CreateOrganizationService} deliberately never creates one).
 *
 * <p>{@code requestsPerMinute} can never exceed the hard system-wide cap (ADR-0010 §6.2: "no
 * organization's policy can ever exceed a hard system-wide cap") — enforced here, in the one
 * factory/update path every write goes through, not left to the caller to remember. The cap itself
 * is an operational value (tunable via {@code application.yml}, not a domain constant), so it's a
 * parameter to these methods rather than hardcoded, the same reasoning {@code
 * PlatformAccountExistsChecker}'s own port/adapter split already applies to configuration that
 * belongs in the infrastructure layer, not baked into the domain.
 *
 * <p>Same record-style-accessor PMD suppressions as {@link Organization}, same rationale.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class RateLimitPolicy {

  private final UUID id;
  private final UUID organizationId;
  private final int requestsPerMinute;
  private final Instant createdAt;
  private final Instant updatedAt;

  private RateLimitPolicy(
      final UUID id,
      final UUID organizationId,
      final int requestsPerMinute,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.requestsPerMinute = requestsPerMinute;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }

  /** A brand-new policy for an Organization that has never had one set before. */
  public static RateLimitPolicy define(
      final UUID organizationId, final int requestsPerMinute, final int hardSystemWideCap) {
    final Instant now = Instant.now();
    return new RateLimitPolicy(
        UUID.randomUUID(),
        organizationId,
        requireWithinHardCap(requestsPerMinute, hardSystemWideCap),
        now,
        now);
  }

  /**
   * A real row already exists for this Organization — replaces its ceiling, keeping the original
   * {@code id}/{@code createdAt} (same "update in place, never a second row" convention as {@code
   * PasswordCredential}'s own reset path) and stamping a fresh {@code updatedAt} for the audit
   * trail TD-SEC-007 will eventually read.
   */
  public RateLimitPolicy withRequestsPerMinute(
      final int requestsPerMinute, final int hardSystemWideCap) {
    return new RateLimitPolicy(
        id,
        organizationId,
        requireWithinHardCap(requestsPerMinute, hardSystemWideCap),
        createdAt,
        Instant.now());
  }

  public static RateLimitPolicy reconstitute(
      final UUID id,
      final UUID organizationId,
      final int requestsPerMinute,
      final Instant createdAt,
      final Instant updatedAt) {
    return new RateLimitPolicy(id, organizationId, requestsPerMinute, createdAt, updatedAt);
  }

  private static int requireWithinHardCap(
      final int requestsPerMinute, final int hardSystemWideCap) {
    if (requestsPerMinute <= 0) {
      throw new IllegalArgumentException("requestsPerMinute must be positive");
    }
    if (requestsPerMinute > hardSystemWideCap) {
      throw new IllegalArgumentException(
          "requestsPerMinute ("
              + requestsPerMinute
              + ") must not exceed the hard system-wide cap ("
              + hardSystemWideCap
              + ") — ADR-0010 §6.2");
    }
    return requestsPerMinute;
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public int requestsPerMinute() {
    return requestsPerMinute;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
