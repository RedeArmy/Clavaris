package com.clavaris.organization.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Persistence shape of {@code OrganizationLogo}: the image itself, apart from the profile. */
@Entity
@Table(name = "organization_logos")
public class OrganizationLogoEntity {

  @Id
  @Column(name = "organization_id")
  private UUID organizationId;

  @Column(name = "content_type", nullable = false)
  private String contentType;

  @Column(nullable = false)
  private byte[] content;

  protected OrganizationLogoEntity() {}

  public OrganizationLogoEntity(
      final UUID organizationId, final String contentType, final byte[] content) {
    this.organizationId = organizationId;
    this.contentType = contentType;
    this.content = content.clone();
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getContentType() {
    return contentType;
  }

  public byte[] getContent() {
    return content.clone();
  }
}
