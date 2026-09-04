package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.domain.model.SocialProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code organization_social_credentials} (ADR-0022) — plain data holder by
 * design, same convention as {@code RateLimitPolicyEntity}.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass", "PMD.LongVariable"})
@Entity
@Table(name = "organization_social_credentials")
public class OrganizationSocialCredentialEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider", nullable = false, length = 20)
  private SocialProvider provider;

  @Column(name = "client_id", nullable = false)
  private String clientId;

  @Column(name = "client_secret_encrypted", nullable = false)
  private String clientSecretEncrypted;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected OrganizationSocialCredentialEntity() {}

  @SuppressWarnings("java:S107")
  public OrganizationSocialCredentialEntity(
      final UUID id,
      final UUID organizationId,
      final SocialProvider provider,
      final String clientId,
      final String clientSecretEncrypted,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = id;
    this.organizationId = organizationId;
    this.provider = provider;
    this.clientId = clientId;
    this.clientSecretEncrypted = clientSecretEncrypted;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public SocialProvider getProvider() {
    return provider;
  }

  public String getClientId() {
    return clientId;
  }

  public String getClientSecretEncrypted() {
    return clientSecretEncrypted;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
