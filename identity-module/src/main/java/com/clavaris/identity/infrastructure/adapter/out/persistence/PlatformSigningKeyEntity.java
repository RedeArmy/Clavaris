package com.clavaris.identity.infrastructure.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code platform_signing_keys} (data-model.md §2). Shared columns live on
 * {@link AbstractSigningKeyEntity} (TD-ARCH-009, closed 2026-08-31) — this table adds no owning-id
 * column at all, see that class's own Javadoc for why.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "platform_signing_keys")
public class PlatformSigningKeyEntity extends AbstractSigningKeyEntity {

  protected PlatformSigningKeyEntity() {
    super();
  }

  public PlatformSigningKeyEntity(
      final UUID id,
      final String kid,
      final String algorithm,
      final Instant activeFrom,
      final Instant retiredAt) {
    super(id, kid, algorithm, activeFrom, retiredAt);
  }
}
