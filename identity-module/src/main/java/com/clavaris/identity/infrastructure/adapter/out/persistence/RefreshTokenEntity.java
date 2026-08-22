package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA row mapping for {@code refresh_tokens} (data-model.md §2, BR-ID-03). */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {

  @Id private UUID id;

  @Column(name = "session_id", nullable = false)
  private UUID sessionId;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "token_hash", nullable = false)
  private String tokenHash;

  @Column(name = "rotated_from_id")
  private UUID rotatedFromId;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected RefreshTokenEntity() {}

  // One parameter per persisted column — same convention as every other *Entity in this codebase
  // (PasswordCredentialEntity, SigningKeyEntity, OAuthClientEntity, ...) that crosses 7 columns.
  @SuppressWarnings("java:S107")
  public RefreshTokenEntity(
      final UUID id,
      final UUID sessionId,
      final UUID accountId,
      final String tokenHash,
      final UUID rotatedFromId,
      final Instant issuedAt,
      final Instant expiresAt,
      final Instant revokedAt) {
    this.id = id;
    this.sessionId = sessionId;
    this.accountId = accountId;
    this.tokenHash = tokenHash;
    this.rotatedFromId = rotatedFromId;
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
    this.revokedAt = revokedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSessionId() {
    return sessionId;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public UUID getRotatedFromId() {
    return rotatedFromId;
  }

  public Instant getIssuedAt() {
    return issuedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }
}
