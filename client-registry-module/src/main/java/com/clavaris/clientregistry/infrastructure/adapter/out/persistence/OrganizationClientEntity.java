package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code organization_clients} (ADR-0023) — plain data holder by design, same
 * convention as {@code PlatformClientEntity}.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "organization_clients")
public class OrganizationClientEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

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

  protected OrganizationClientEntity() {}

  @SuppressWarnings("java:S107")
  public OrganizationClientEntity(
      final UUID id,
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final String allowedScopes,
      final Instant createdAt,
      final boolean active) {
    this.id = id;
    this.organizationId = organizationId;
    this.clientId = clientId;
    this.clientSecretHash = clientSecretHash;
    this.allowedScopes = allowedScopes;
    this.createdAt = createdAt;
    this.active = active;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
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
