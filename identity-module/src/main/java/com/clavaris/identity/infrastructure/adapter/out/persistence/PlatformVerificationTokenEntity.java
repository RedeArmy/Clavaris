package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code platform_verification_tokens} — mirrors {@link
 * VerificationTokenEntity}, same "type as a plain String" convention.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "platform_verification_tokens")
public class PlatformVerificationTokenEntity {

  @Id private UUID id;

  @Column(name = "platform_account_id", nullable = false)
  private UUID platformAccountId;

  @Column(nullable = false, length = 32)
  private String type;

  @Column(name = "token_hash", nullable = false)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  protected PlatformVerificationTokenEntity() {}

  @SuppressWarnings("java:S107")
  public PlatformVerificationTokenEntity(
      final UUID id,
      final UUID platformAccountId,
      final String type,
      final String tokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    this.id = id;
    this.platformAccountId = platformAccountId;
    this.type = type;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
    this.consumedAt = consumedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPlatformAccountId() {
    return platformAccountId;
  }

  public String getType() {
    return type;
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
