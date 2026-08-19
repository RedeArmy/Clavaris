package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA row mapping for {@code signing_keys} (data-model.md §2, per-Organization — BR-ORG-04). */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "signing_keys")
public class SigningKeyEntity {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false)
  private String kid;

  @Column(nullable = false)
  private String algorithm;

  @Column(name = "active_from", nullable = false)
  private Instant activeFrom;

  @Column(name = "retired_at")
  private Instant retiredAt;

  protected SigningKeyEntity() {}

  public SigningKeyEntity(
      final UUID id,
      final UUID organizationId,
      final String kid,
      final String algorithm,
      final Instant activeFrom,
      final Instant retiredAt) {
    this.id = id;
    this.organizationId = organizationId;
    this.kid = kid;
    this.algorithm = algorithm;
    this.activeFrom = activeFrom;
    this.retiredAt = retiredAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getKid() {
    return kid;
  }

  public String getAlgorithm() {
    return algorithm;
  }

  public Instant getActiveFrom() {
    return activeFrom;
  }

  public Instant getRetiredAt() {
    return retiredAt;
  }
}
