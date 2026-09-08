package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared state and lifecycle for {@link KnownDevice}/{@link PlatformKnownDevice} — same TD-ARCH-009
 * extraction {@link AbstractVerificationToken} already established for the structurally identical
 * {@code VerificationToken}/{@code PlatformVerificationToken} pair, applied here to a pair that
 * post-dated that sweep (TD-SEC-033, 2026-08-31, and its own platform-tier mirror TD-FUT-026,
 * 2026-09-02, both shipped after TD-ARCH-009's own closure the same day as the first one). {@link
 * #touch()} and every field except the owning id are provably, permanently identical between the
 * two — the anti-spoofing device-token design ({@link KnownDevice}'s own Javadoc, TD-SEC-033) is
 * the same security property at either tier, not a coincidence either copy could plausibly diverge
 * from later.
 *
 * <p>Same "generic, not a mirror" reasoning as {@link AbstractVerificationToken}'s own Javadoc —
 * this class has no side effects and no tier-specific behavior at all; {@code I} is the only thing
 * that differs.
 *
 * <p>Package-private: only this package's own two subclasses ever need to see it. Same
 * record-style-accessor and structural-metric PMD suppressions as {@link
 * AbstractVerificationToken}, same rationale.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.AbstractClassWithoutAbstractMethod",
  "PMD.PublicMemberInNonPublicType",
  "PMD.DataClass"
})
abstract class AbstractKnownDevice<I> {

  private final UUID id;
  private final I owningId;
  private final String userAgent;
  private final String deviceTokenHash;
  private final Instant firstSeenAt;
  private Instant lastSeenAt;

  protected AbstractKnownDevice(
      final UUID id,
      final I owningId,
      final String userAgent,
      final String deviceTokenHash,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.owningId = Objects.requireNonNull(owningId, "owningId must not be null");
    this.userAgent = Objects.requireNonNull(userAgent, "userAgent must not be null");
    this.deviceTokenHash =
        Objects.requireNonNull(deviceTokenHash, "deviceTokenHash must not be null");
    this.firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt must not be null");
    this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
  }

  /** Called on every subsequent login from an already-known device — no notification, just this. */
  public final void touch() {
    this.lastSeenAt = Instant.now();
  }

  public final UUID id() {
    return id;
  }

  protected final I owningId() {
    return owningId;
  }

  public final String userAgent() {
    return userAgent;
  }

  public final String deviceTokenHash() {
    return deviceTokenHash;
  }

  public final Instant firstSeenAt() {
    return firstSeenAt;
  }

  public final Instant lastSeenAt() {
    return lastSeenAt;
  }
}
