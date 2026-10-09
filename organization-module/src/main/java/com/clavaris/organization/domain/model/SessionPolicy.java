package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Clerk "Sessions" settings parity — the four per-{@code Organization} tunables governing how long
 * a consuming system's own session stays valid, and whether an Account may hold more than one
 * active session at a time. Absence of a row for a given Organization means "use the system
 * default" (see {@link #defaults(UUID)}), not "unconfigured" — same "no row = default, never an
 * error" convention {@link RateLimitPolicy}'s own Javadoc already establishes.
 *
 * <p>Every bound below is a fixed product constant (not operator-tunable the way {@link
 * RateLimitPolicy}'s own hard system-wide cap is), so it's enforced directly in this class rather
 * than threaded through every factory/update call the way that cap is.
 *
 * <p>Same record-style-accessor PMD suppressions as {@link RateLimitPolicy}, same rationale.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods",
  "PMD.LongVariable"
})
public final class SessionPolicy {

  private static final int MIN_MAXIMUM_LIFETIME_MINUTES = 5;
  private static final int MAX_MAXIMUM_LIFETIME_MINUTES = 5_256_000; // 10 years
  private static final int MIN_INACTIVITY_TIMEOUT_MINUTES = 5;
  private static final int MAX_INACTIVITY_TIMEOUT_MINUTES = 525_600; // 1 year
  private static final int MIN_REVERIFICATION_WINDOW_MINUTES = 1;
  private static final int MAX_REVERIFICATION_WINDOW_MINUTES = 10;

  // Clerk's own real defaults for the first three; multi-session handling defaults to enabled.
  private static final int DEFAULT_MAXIMUM_LIFETIME_MINUTES = 10_080; // 7 days
  private static final int DEFAULT_INACTIVITY_TIMEOUT_MINUTES = 10_080; // 7 days
  private static final int DEFAULT_REVERIFICATION_WINDOW_MINUTES = 10;

  private final UUID id;
  private final UUID organizationId;
  private final int maximumLifetimeMinutes;
  private final int inactivityTimeoutMinutes;
  private final int reverificationWindowMinutes;
  private final boolean multiSessionHandlingEnabled;
  private final Instant createdAt;
  private final Instant updatedAt;

  @SuppressWarnings("java:S107")
  private SessionPolicy(
      final UUID id,
      final UUID organizationId,
      final int maximumLifetimeMinutes,
      final int inactivityTimeoutMinutes,
      final int reverificationWindowMinutes,
      final boolean multiSessionHandlingEnabled,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.maximumLifetimeMinutes =
        requireWithinRange(
            maximumLifetimeMinutes,
            MIN_MAXIMUM_LIFETIME_MINUTES,
            MAX_MAXIMUM_LIFETIME_MINUTES,
            "maximumLifetimeMinutes");
    this.inactivityTimeoutMinutes =
        requireWithinRange(
            inactivityTimeoutMinutes,
            MIN_INACTIVITY_TIMEOUT_MINUTES,
            MAX_INACTIVITY_TIMEOUT_MINUTES,
            "inactivityTimeoutMinutes");
    this.reverificationWindowMinutes =
        requireWithinRange(
            reverificationWindowMinutes,
            MIN_REVERIFICATION_WINDOW_MINUTES,
            MAX_REVERIFICATION_WINDOW_MINUTES,
            "reverificationWindowMinutes");
    this.multiSessionHandlingEnabled = multiSessionHandlingEnabled;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }

  /** The effective policy for an Organization that has never had one explicitly set. */
  public static SessionPolicy defaults(final UUID organizationId) {
    final Instant now = Instant.now();
    return new SessionPolicy(
        UUID.randomUUID(),
        organizationId,
        DEFAULT_MAXIMUM_LIFETIME_MINUTES,
        DEFAULT_INACTIVITY_TIMEOUT_MINUTES,
        DEFAULT_REVERIFICATION_WINDOW_MINUTES,
        true,
        now,
        now);
  }

  /** A brand-new policy for an Organization that has never had one set before. */
  @SuppressWarnings("java:S107")
  public static SessionPolicy define(
      final UUID organizationId,
      final int maximumLifetimeMinutes,
      final int inactivityTimeoutMinutes,
      final int reverificationWindowMinutes,
      final boolean multiSessionHandlingEnabled) {
    final Instant now = Instant.now();
    return new SessionPolicy(
        UUID.randomUUID(),
        organizationId,
        maximumLifetimeMinutes,
        inactivityTimeoutMinutes,
        reverificationWindowMinutes,
        multiSessionHandlingEnabled,
        now,
        now);
  }

  /**
   * A real row already exists for this Organization — replaces its tunables, keeping the original
   * {@code id}/{@code createdAt} and stamping a fresh {@code updatedAt}, same convention {@link
   * RateLimitPolicy#withRequestsPerMinute} already establishes.
   */
  public SessionPolicy withPolicy(
      final int maximumLifetimeMinutes,
      final int inactivityTimeoutMinutes,
      final int reverificationWindowMinutes,
      final boolean multiSessionHandlingEnabled) {
    return new SessionPolicy(
        id,
        organizationId,
        maximumLifetimeMinutes,
        inactivityTimeoutMinutes,
        reverificationWindowMinutes,
        multiSessionHandlingEnabled,
        createdAt,
        Instant.now());
  }

  @SuppressWarnings("java:S107")
  public static SessionPolicy reconstitute(
      final UUID id,
      final UUID organizationId,
      final int maximumLifetimeMinutes,
      final int inactivityTimeoutMinutes,
      final int reverificationWindowMinutes,
      final boolean multiSessionHandlingEnabled,
      final Instant createdAt,
      final Instant updatedAt) {
    return new SessionPolicy(
        id,
        organizationId,
        maximumLifetimeMinutes,
        inactivityTimeoutMinutes,
        reverificationWindowMinutes,
        multiSessionHandlingEnabled,
        createdAt,
        updatedAt);
  }

  private static int requireWithinRange(
      final int value, final int min, final int max, final String fieldName) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          fieldName + " (" + value + ") must be between " + min + " and " + max + " minutes");
    }
    return value;
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public int maximumLifetimeMinutes() {
    return maximumLifetimeMinutes;
  }

  public int inactivityTimeoutMinutes() {
    return inactivityTimeoutMinutes;
  }

  public int reverificationWindowMinutes() {
    return reverificationWindowMinutes;
  }

  public boolean multiSessionHandlingEnabled() {
    return multiSessionHandlingEnabled;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
