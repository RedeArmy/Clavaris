package com.clavaris.organization.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA row mapping for {@code organizations} (data-model.md §2, ADR-0010). */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "organizations")
public class OrganizationEntity {

  @Id private UUID id;

  @Column(nullable = false)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected OrganizationEntity() {}

  public OrganizationEntity(final UUID id, final String name, final Instant createdAt) {
    this.id = id;
    this.name = name;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
