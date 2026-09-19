package com.clavaris.organization.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA row mapping for {@code access_restriction_entries} — plain data holder by design. */
@SuppressWarnings({"PMD.ShortVariable", "PMD.DataClass"})
@Entity
@Table(name = "access_restriction_entries")
public class AccessRestrictionEntryEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private String type;

  @Column(nullable = false)
  private String identifier;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AccessRestrictionEntryEntity() {}

  public AccessRestrictionEntryEntity(
      final UUID id,
      final UUID organizationId,
      final String type,
      final String identifier,
      final Instant createdAt) {
    this.id = id;
    this.organizationId = organizationId;
    this.type = type;
    this.identifier = identifier;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getType() {
    return type;
  }

  public String getIdentifier() {
    return identifier;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
