package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code platform_known_devices} (TD-FUT-026, platform-tier mirror of {@link
 * KnownDeviceEntity}).
 *
 * <p>Shared columns live on {@link AbstractKnownDeviceEntity} (TD-ARCH-009) — only {@code
 * platform_account_id} is declared here.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "platform_known_devices")
public class PlatformKnownDeviceEntity extends AbstractKnownDeviceEntity {

  @Column(name = "platform_account_id", nullable = false)
  private UUID platformAccountId;

  protected PlatformKnownDeviceEntity() {
    super();
  }

  @SuppressWarnings("java:S107")
  public PlatformKnownDeviceEntity(
      final UUID id,
      final UUID platformAccountId,
      final String userAgent,
      final String deviceTokenHash,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    super(id, userAgent, deviceTokenHash, firstSeenAt, lastSeenAt);
    this.platformAccountId = platformAccountId;
  }

  public UUID getPlatformAccountId() {
    return platformAccountId;
  }
}
