package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA row mapping for {@code known_devices} (data-model.md, new-device-login notification). */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "known_devices")
public class KnownDeviceEntity {

  @Id private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "user_agent", nullable = false, length = 512)
  private String userAgent;

  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  protected KnownDeviceEntity() {}

  public KnownDeviceEntity(
      final UUID id,
      final UUID accountId,
      final String userAgent,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    this.id = id;
    this.accountId = accountId;
    this.userAgent = userAgent;
    this.firstSeenAt = firstSeenAt;
    this.lastSeenAt = lastSeenAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }
}
