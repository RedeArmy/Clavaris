package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** TD-FUT-043: {@code email_change_requests} (V20261027100000). */
@Entity
@Table(name = "email_change_requests")
public class EmailChangeRequestEntity {

  @Id private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "new_email", nullable = false)
  private String newEmail;

  @Column(name = "token_hash", nullable = false)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  protected EmailChangeRequestEntity() {}

  public EmailChangeRequestEntity(
      final UUID id,
      final UUID accountId,
      final String newEmail,
      final String tokenHash,
      final Instant expiresAt) {
    this.id = id;
    this.accountId = accountId;
    this.newEmail = newEmail;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public String getNewEmail() {
    return newEmail;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }
}
