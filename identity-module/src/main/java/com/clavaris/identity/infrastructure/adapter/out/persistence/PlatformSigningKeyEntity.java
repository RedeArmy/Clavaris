package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA row mapping for {@code platform_signing_keys} (data-model.md §2). */
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable"})
@Entity
@Table(name = "platform_signing_keys")
public class PlatformSigningKeyEntity {

  @Id private UUID id;

  @Column(nullable = false)
  private String kid;

  @Column(nullable = false)
  private String algorithm;

  @Column(name = "active_from", nullable = false)
  private Instant activeFrom;

  @Column(name = "retired_at")
  private Instant retiredAt;

  protected PlatformSigningKeyEntity() {}

  public PlatformSigningKeyEntity(
      final UUID id,
      final String kid,
      final String algorithm,
      final Instant activeFrom,
      final Instant retiredAt) {
    this.id = id;
    this.kid = kid;
    this.algorithm = algorithm;
    this.activeFrom = activeFrom;
    this.retiredAt = retiredAt;
  }

  public UUID getId() {
    return id;
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
