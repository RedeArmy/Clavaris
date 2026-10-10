package com.clavaris.organization.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Persistence shape of {@code OrganizationProfile}: one optional row per Organization. */
@SuppressWarnings("PMD.DataClass")
@Entity
@Table(name = "organization_profiles")
public class OrganizationProfileEntity {

  @Id
  @Column(name = "organization_id")
  private UUID organizationId;

  @Column private String description;

  @Column(name = "application_name")
  private String applicationName;

  @Column(name = "brand_color")
  private String brandColor;

  @Column(name = "logo_updated_at")
  private Instant logoUpdatedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected OrganizationProfileEntity() {}

  @SuppressWarnings("java:S107") // one parameter per persisted column
  public OrganizationProfileEntity(
      final UUID organizationId,
      final String description,
      final String applicationName,
      final String brandColor,
      final Instant logoUpdatedAt,
      final Instant updatedAt) {
    this.organizationId = organizationId;
    this.description = description;
    this.applicationName = applicationName;
    this.brandColor = brandColor;
    this.logoUpdatedAt = logoUpdatedAt;
    this.updatedAt = updatedAt;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getDescription() {
    return description;
  }

  public String getApplicationName() {
    return applicationName;
  }

  public String getBrandColor() {
    return brandColor;
  }

  public Instant getLogoUpdatedAt() {
    return logoUpdatedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
