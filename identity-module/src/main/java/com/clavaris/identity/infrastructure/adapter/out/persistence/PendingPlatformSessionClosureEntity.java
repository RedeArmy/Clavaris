package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** TD-ARCH-063: see {@code PendingPlatformSessionClosureRepository}. */
@Entity
@Table(name = "pending_platform_session_closures")
public class PendingPlatformSessionClosureEntity {

  @Id
  @Column(name = "platform_account_id")
  private UUID platformAccountId;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  protected PendingPlatformSessionClosureEntity() {}

  public PendingPlatformSessionClosureEntity(
      final UUID platformAccountId, final Instant recordedAt) {
    this.platformAccountId = platformAccountId;
    this.recordedAt = recordedAt;
  }

  public UUID getPlatformAccountId() {
    return platformAccountId;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }
}
