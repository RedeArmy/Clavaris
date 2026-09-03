package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * TD-FUT-026 (closed 2026-09-02): the platform-tier mirror of {@link KnownDevice} — a device this
 * {@code PlatformAccount} has successfully logged in from before, matched by the same opaque,
 * high-entropy device token design (TD-SEC-033), not the raw {@code User-Agent} header. See {@link
 * KnownDevice}'s own Javadoc for the full anti-spoofing rationale; every word of it applies here
 * unchanged, just against {@link PlatformAccountId} instead of {@link AccountId} — there is no
 * {@code Organization} to further scope this by, same "platform tier is not multi-tenant" shape
 * {@link PlatformAccountId} itself already establishes.
 *
 * <p>Same PMD suppressions and the same reason as {@link KnownDevice}'s own class-level Javadoc.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class PlatformKnownDevice {

  private final UUID id;
  private final PlatformAccountId platformAccountId;
  private final String userAgent;
  private final String deviceTokenHash;
  private final Instant firstSeenAt;
  private Instant lastSeenAt;

  private PlatformKnownDevice(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final String userAgent,
      final String deviceTokenHash,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.platformAccountId =
        Objects.requireNonNull(platformAccountId, "platformAccountId must not be null");
    this.userAgent = Objects.requireNonNull(userAgent, "userAgent must not be null");
    this.deviceTokenHash =
        Objects.requireNonNull(deviceTokenHash, "deviceTokenHash must not be null");
    this.firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt must not be null");
    this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
  }

  /**
   * @param deviceTokenHash already hashed — hashing happens in the application layer ({@code
   *     RecordPlatformAccountLoginDeviceService}, via {@code RefreshTokenSecret}), never here, same
   *     split as {@link KnownDevice#recognize}.
   */
  public static PlatformKnownDevice recognize(
      final PlatformAccountId platformAccountId,
      final String userAgent,
      final String deviceTokenHash) {
    final Instant now = Instant.now();
    return new PlatformKnownDevice(
        UUID.randomUUID(), platformAccountId, userAgent, deviceTokenHash, now, now);
  }

  public static PlatformKnownDevice reconstitute(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final String userAgent,
      final String deviceTokenHash,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    return new PlatformKnownDevice(
        id, platformAccountId, userAgent, deviceTokenHash, firstSeenAt, lastSeenAt);
  }

  /** Called on every subsequent login from an already-known device — no notification, just this. */
  public void touch() {
    this.lastSeenAt = Instant.now();
  }

  public UUID id() {
    return id;
  }

  public PlatformAccountId platformAccountId() {
    return platformAccountId;
  }

  public String userAgent() {
    return userAgent;
  }

  public String deviceTokenHash() {
    return deviceTokenHash;
  }

  public Instant firstSeenAt() {
    return firstSeenAt;
  }

  public Instant lastSeenAt() {
    return lastSeenAt;
  }
}
