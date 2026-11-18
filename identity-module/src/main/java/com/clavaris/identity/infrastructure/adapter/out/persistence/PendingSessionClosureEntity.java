package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** TD-ARCH-057: see {@code PendingSessionClosureRepository}. */
@Entity
@Table(name = "pending_session_closures")
public class PendingSessionClosureEntity {

  @Id
  @Column(name = "account_id")
  private UUID accountId;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  protected PendingSessionClosureEntity() {}

  public PendingSessionClosureEntity(final UUID accountId, final Instant recordedAt) {
    this.accountId = accountId;
    this.recordedAt = recordedAt;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }
}
