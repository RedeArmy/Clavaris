package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared column mapping for {@link KnownDeviceEntity}/{@link PlatformKnownDeviceEntity} — same
 * {@code @MappedSuperclass} extraction {@link AbstractVerificationTokenEntity} already established
 * for the structurally identical {@code VerificationTokenEntity}/{@code
 * PlatformVerificationTokenEntity} pair (TD-ARCH-009), applied here to a pair that post-dated that
 * sweep (TD-SEC-033/TD-FUT-026, both shipped after TD-ARCH-009's own closure).
 *
 * <p>Every column here except the owning-id one (added by each subclass — {@code account_id} vs.
 * {@code platform_account_id}, different tables entirely) is identical between both tables — pure
 * persistence boilerplate with zero domain meaning of its own ({@code coding-standards.md} §5's own
 * "would only ever change in lockstep" test).
 *
 * <p>No abstract method of its own — same PMD.AbstractClassWithoutAbstractMethod rationale as
 * {@link AbstractVerificationTokenEntity}'s own identical suppression.
 */
@MappedSuperclass
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable", "PMD.AbstractClassWithoutAbstractMethod"})
public abstract class AbstractKnownDeviceEntity {

  @Id protected UUID id;

  @Column(name = "user_agent", nullable = false, length = 512)
  protected String userAgent;

  @Column(name = "device_token_hash")
  protected String deviceTokenHash;

  @Column(name = "first_seen_at", nullable = false)
  protected Instant firstSeenAt;

  @Column(name = "last_seen_at", nullable = false)
  protected Instant lastSeenAt;

  protected AbstractKnownDeviceEntity() {}

  // One parameter per shared column — same convention AbstractVerificationTokenEntity's own
  // identical suppression documents for this exact shape of constructor.
  @SuppressWarnings("java:S107")
  protected AbstractKnownDeviceEntity(
      final UUID id,
      final String userAgent,
      final String deviceTokenHash,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    this.id = id;
    this.userAgent = userAgent;
    this.deviceTokenHash = deviceTokenHash;
    this.firstSeenAt = firstSeenAt;
    this.lastSeenAt = lastSeenAt;
  }

  public UUID getId() {
    return id;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public String getDeviceTokenHash() {
    return deviceTokenHash;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }
}
