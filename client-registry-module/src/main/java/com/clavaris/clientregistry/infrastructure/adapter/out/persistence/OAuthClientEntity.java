package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code oauth_clients} (data-model.md §2). {@code redirectUris}/{@code
 * allowedGrantTypes}/{@code allowedScopes} are stored as {@code text} (JSON array) — same
 * convention as {@link PlatformClientEntity#getAllowedScopes()}; serialization happens in {@link
 * JpaOAuthClientRepository}, not here.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "oauth_clients")
public class OAuthClientEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "client_id", nullable = false)
  private String clientId;

  @Column(name = "client_secret_hash", nullable = false)
  private String clientSecretHash;

  @Column(name = "redirect_uris", nullable = false, columnDefinition = "text")
  private String redirectUris;

  @Column(name = "allowed_grant_types", nullable = false, columnDefinition = "text")
  private String allowedGrantTypes;

  @Column(name = "allowed_scopes", nullable = false, columnDefinition = "text")
  private String allowedScopes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected OAuthClientEntity() {}

  public OAuthClientEntity(
      final UUID id,
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final String redirectUris,
      final String allowedGrantTypes,
      final String allowedScopes,
      final Instant createdAt) {
    this.id = id;
    this.organizationId = organizationId;
    this.clientId = clientId;
    this.clientSecretHash = clientSecretHash;
    this.redirectUris = redirectUris;
    this.allowedGrantTypes = allowedGrantTypes;
    this.allowedScopes = allowedScopes;
    this.createdAt = createdAt;
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

  public String getRedirectUris() {
    return redirectUris;
  }

  public String getAllowedGrantTypes() {
    return allowedGrantTypes;
  }

  public String getAllowedScopes() {
    return allowedScopes;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
