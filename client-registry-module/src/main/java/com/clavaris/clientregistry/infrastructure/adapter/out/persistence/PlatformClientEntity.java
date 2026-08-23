package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code platform_clients} (data-model.md §2). {@code allowedScopes} is stored
 * as {@code text} (JSON array) — same convention data-model.md documents for {@code
 * oauth_clients.allowed_scopes} — serialization happens in {@link JpaPlatformClientRepository}, not
 * here, same "entity is a plain persistence-mapping data holder" discipline as identity-module's
 * own JPA entities.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "platform_clients")
public class PlatformClientEntity {

  @Id private UUID id;

  @Column(name = "client_id", nullable = false)
  private String clientId;

  @Column(name = "client_secret_hash", nullable = false)
  private String clientSecretHash;

  @Column(name = "allowed_scopes", nullable = false, columnDefinition = "text")
  private String allowedScopes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private boolean active;

  protected PlatformClientEntity() {}

  public PlatformClientEntity(
      final UUID id,
      final String clientId,
      final String clientSecretHash,
      final String allowedScopes,
      final Instant createdAt,
      final boolean active) {
    this.id = id;
    this.clientId = clientId;
    this.clientSecretHash = clientSecretHash;
    this.allowedScopes = allowedScopes;
    this.createdAt = createdAt;
    this.active = active;
  }

  public UUID getId() {
    return id;
  }

  public String getClientId() {
    return clientId;
  }

  public String getClientSecretHash() {
    return clientSecretHash;
  }

  public String getAllowedScopes() {
    return allowedScopes;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public boolean isActive() {
    return active;
  }
}
