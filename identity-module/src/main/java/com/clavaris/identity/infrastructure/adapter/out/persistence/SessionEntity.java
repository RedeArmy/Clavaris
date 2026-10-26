package com.clavaris.identity.infrastructure.adapter.out.persistence;

import com.clavaris.identity.domain.model.SessionTimestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA row mapping for {@code sessions} (data-model.md §2, BR-ID-03). */
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

  @Column(name = "authenticated_at", nullable = false)
  private Instant authenticatedAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "oidc_sid")
  private String oidcSid;

  protected SessionEntity() {}

  public SessionEntity(
      final UUID id,
      final UUID accountId,
      final String scopes,
      final SessionTimestamps timestamps,
      final Instant revokedAt,
      final String oidcSid) {
    this.id = id;
    this.accountId = accountId;
    this.scopes = scopes;
    this.createdAt = timestamps.createdAt();
    this.authenticatedAt = timestamps.authenticatedAt();
    this.lastSeenAt = timestamps.lastSeenAt();
    this.revokedAt = revokedAt;
    this.oidcSid = oidcSid;
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

  public Instant getAuthenticatedAt() {
    return authenticatedAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public String getOidcSid() {
    return oidcSid;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }
}
