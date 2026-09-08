package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code known_devices} (data-model.md, new-device-login notification,
 * TD-SEC-033).
 *
 * <p>Shared columns live on {@link AbstractKnownDeviceEntity} — only {@code account_id} is declared
 * here (TD-ARCH-009), same split {@link VerificationTokenEntity} already establishes against its
 * own {@link AbstractVerificationTokenEntity}.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "known_devices")
public class KnownDeviceEntity extends AbstractKnownDeviceEntity {

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  protected KnownDeviceEntity() {
    super();
  }

  // One parameter per persisted column — same convention as every other *Entity in this codebase
  // (RefreshTokenEntity, SigningKeyEntity, OAuthClientEntity, ...) that crosses 7 columns.
  @SuppressWarnings("java:S107")
  public KnownDeviceEntity(
      final UUID id,
      final UUID accountId,
      final String userAgent,
      final String deviceTokenHash,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    super(id, userAgent, deviceTokenHash, firstSeenAt, lastSeenAt);
    this.accountId = accountId;
  }

  public UUID getAccountId() {
    return accountId;
  }
}
