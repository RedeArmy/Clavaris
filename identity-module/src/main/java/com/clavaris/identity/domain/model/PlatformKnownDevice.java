package com.clavaris.identity.domain.model;

import java.time.Instant;
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
 * <p>Shared state/lifecycle lives on {@link AbstractKnownDevice} — see its own Javadoc for why this
 * pair shares a base (TD-ARCH-009).
 *
 * <p>PMD.ShortVariable: {@code id} names exactly what it is — same convention {@link
 * AbstractKnownDevice}'s own identical suppression already documents for this same constructor
 * parameter.
 */
@SuppressWarnings("PMD.ShortVariable")
public final class PlatformKnownDevice extends AbstractKnownDevice<PlatformAccountId> {

  private PlatformKnownDevice(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final String userAgent,
      final String deviceTokenHash,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    super(id, platformAccountId, userAgent, deviceTokenHash, firstSeenAt, lastSeenAt);
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

  public PlatformAccountId platformAccountId() {
    return owningId();
  }
}
