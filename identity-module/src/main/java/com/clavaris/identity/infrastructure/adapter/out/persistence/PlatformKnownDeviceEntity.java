package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code platform_known_devices} (TD-FUT-026, platform-tier mirror of {@link
 * KnownDeviceEntity}).
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "platform_known_devices")
public class PlatformKnownDeviceEntity {

  @Id private UUID id;

  @Column(name = "platform_account_id", nullable = false)
  private UUID platformAccountId;

  @Column(name = "user_agent", nullable = false, length = 512)
  private String userAgent;

  @Column(name = "device_token_hash")
  private String deviceTokenHash;

  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  protected PlatformKnownDeviceEntity() {}

  @SuppressWarnings("java:S107")
  public PlatformKnownDeviceEntity(
      final UUID id,
      final UUID platformAccountId,
      final String userAgent,
      final String deviceTokenHash,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    this.id = id;
    this.platformAccountId = platformAccountId;
    this.userAgent = userAgent;
    this.deviceTokenHash = deviceTokenHash;
    this.firstSeenAt = firstSeenAt;
    this.lastSeenAt = lastSeenAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPlatformAccountId() {
    return platformAccountId;
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
