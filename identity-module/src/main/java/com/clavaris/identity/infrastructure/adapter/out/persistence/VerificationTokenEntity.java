package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code verification_tokens} (data-model.md §2, BR-ID-04/BR-ID-05). {@code
 * type} is a plain {@code String}, not the domain's {@code VerificationTokenType} enum — same
 * convention as {@code AccountEntity.status}: this entity never references a {@code domain.model}
 * type at all, the mapping adapter ({@code JpaVerificationTokenRepository}) owns the conversion.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "verification_tokens")
public class VerificationTokenEntity {

  @Id private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(nullable = false, length = 32)
  private String type;

  @Column(name = "token_hash", nullable = false)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  protected VerificationTokenEntity() {}

  // One parameter per persisted column — same convention as every other *Entity in this codebase
  // (RefreshTokenEntity, SigningKeyEntity, OAuthClientEntity, ...) that crosses 7 columns.
  @SuppressWarnings("java:S107")
  public VerificationTokenEntity(
      final UUID id,
      final UUID accountId,
      final String type,
      final String tokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    this.id = id;
    this.accountId = accountId;
    this.type = type;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
    this.consumedAt = consumedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAccountId() {
    return accountId;
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
