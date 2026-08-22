package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA row mapping for {@code sessions} (data-model.md §2, BR-ID-03). */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "sessions")
public class SessionEntity {

  @Id private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  // JSON array (matches OAuthClientEntity's redirectUris/allowedGrantTypes/allowedScopes
  // convention) — serialized/deserialized via ObjectMapper in JpaSessionRepository, never mapped
  // as a native array/jsonb column type.
  @Column(nullable = false)
  private String scopes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected SessionEntity() {}

  public SessionEntity(
      final UUID id,
      final UUID accountId,
      final String scopes,
      final Instant createdAt,
      final Instant lastSeenAt,
      final Instant revokedAt) {
    this.id = id;
    this.accountId = accountId;
    this.scopes = scopes;
    this.createdAt = createdAt;
    this.lastSeenAt = lastSeenAt;
    this.revokedAt = revokedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public String getScopes() {
    return scopes;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }
}
