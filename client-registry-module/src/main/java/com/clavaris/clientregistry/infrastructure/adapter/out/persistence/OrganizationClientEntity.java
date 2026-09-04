package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code organization_clients} (ADR-0023) — plain data holder by design, same
 * convention as {@code PlatformClientEntity}. Column mapping shared with {@link
 * PlatformClientEntity} via {@link AbstractClientCredentialEntity}; {@code organizationId} is this
 * credential's own addition on top of that shared shape.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "organization_clients")
public class OrganizationClientEntity extends AbstractClientCredentialEntity {

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  protected OrganizationClientEntity() {
    super();
  }

  @SuppressWarnings("java:S107")
  public OrganizationClientEntity(
      final UUID id,
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final String allowedScopes,
      final Instant createdAt,
      final boolean active) {
    super(id, clientId, clientSecretHash, allowedScopes, createdAt, active);
    this.organizationId = organizationId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }
}
