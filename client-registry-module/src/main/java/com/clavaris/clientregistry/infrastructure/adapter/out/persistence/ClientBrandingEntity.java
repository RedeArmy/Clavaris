package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code client_brandings} — plain data holder by design, same rationale as
 * {@code RedirectPolicyEntity}. Every column is nullable — unconfigured is the normal state.
 */
// PMD.LongVariable: same SetClientBrandingCommand precedent — applicationDisplayName names
// exactly what ADR-0009 §3 itself calls the field, not arbitrarily long.
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass", "PMD.LongVariable"})
@Entity
@Table(name = "client_brandings")
public class ClientBrandingEntity {

  @Id private UUID id;

  @Column(name = "oauth_client_id", nullable = false)
  private UUID oauthClientId;

  @Column(name = "logo_url")
  private String logoUrl;

  @Column(name = "primary_color")
  private String primaryColor;

  @Column(name = "application_display_name")
  private String applicationDisplayName;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ClientBrandingEntity() {}

  public ClientBrandingEntity(
      final UUID id,
      final UUID oauthClientId,
      final String logoUrl,
      final String primaryColor,
      final String applicationDisplayName,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = id;
    this.oauthClientId = oauthClientId;
    this.logoUrl = logoUrl;
    this.primaryColor = primaryColor;
    this.applicationDisplayName = applicationDisplayName;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOauthClientId() {
    return oauthClientId;
  }

  public String getLogoUrl() {
    return logoUrl;
  }

  public String getPrimaryColor() {
    return primaryColor;
  }

  public String getApplicationDisplayName() {
    return applicationDisplayName;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
