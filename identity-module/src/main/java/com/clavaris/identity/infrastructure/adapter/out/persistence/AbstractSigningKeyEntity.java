package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared column mapping for {@link SigningKeyEntity}/{@link PlatformSigningKeyEntity} — same
 * {@code @MappedSuperclass} extraction {@link AbstractPendingSocialLinkEntity}/{@link
 * AbstractVerificationTokenEntity}/{@link AbstractPasswordCredentialEntity} already established,
 * applied here to TD-ARCH-009's own third pair (named 2026-08-31).
 *
 * <p>Every column here is identical between both tables — {@link PlatformSigningKeyEntity} adds no
 * owning-id column at all (unlike this extraction's other three pairs), same reasoning {@link
 * com.clavaris.identity.domain.model.AbstractSigningKey}'s own Javadoc documents at the domain
 * layer; {@link SigningKeyEntity} is the one that adds its own {@code organization_id} column on
 * top of this base.
 */
@MappedSuperclass
@SuppressWarnings({"PMD.DataClass", "PMD.ShortVariable", "PMD.AbstractClassWithoutAbstractMethod"})
public abstract class AbstractSigningKeyEntity {

  @Id protected UUID id;

  @Column(nullable = false)
  protected String kid;

  @Column(nullable = false)
  protected String algorithm;

  @Column(name = "active_from", nullable = false)
  protected Instant activeFrom;

  @Column(name = "retired_at")
  protected Instant retiredAt;

  protected AbstractSigningKeyEntity() {}

  protected AbstractSigningKeyEntity(
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
