package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code signing_keys} (data-model.md §2, per-Organization — BR-ORG-04). Shared
 * columns live on {@link AbstractSigningKeyEntity} — only {@code organization_id} is declared here
 * (TD-ARCH-009, closed 2026-08-31).
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "signing_keys")
public class SigningKeyEntity extends AbstractSigningKeyEntity {

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  protected SigningKeyEntity() {
    super();
  }

  public SigningKeyEntity(
      final UUID id,
      final UUID organizationId,
      final String kid,
      final String algorithm,
      final Instant activeFrom,
      final Instant retiredAt) {
    super(id, kid, algorithm, activeFrom, retiredAt);
    this.organizationId = organizationId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }
}
