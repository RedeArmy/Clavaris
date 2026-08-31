package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * ADR-0010 (Organization provisioning): metadata for the key signing the platform issuer's own
 * tokens — structurally separate from {@link SigningKey} (which BR-ORG-04 already states every row
 * of belongs to exactly one Organization, no exception carved out here). Deliberately
 * metadata-only, same as {@link SigningKey}: the actual key material is never in this table
 * (data-model.md §2) — see {@code infrastructure.adapter.out.security.PlatformSigningKeyMaterial}
 * for where the real {@code KeyPair} lives (in-memory only in this slice; see that class's own
 * Javadoc for the tracked, deliberate limitation this implies).
 *
 * <p>Shared state/lifecycle lives on {@link AbstractSigningKey} — see its own Javadoc for why this
 * pair shares a base, and specifically why this class adds no owning-id field at all (TD-ARCH-009).
 *
 * <p>PMD.ShortVariable: {@code id} names exactly what it is — same convention {@link
 * AbstractSigningKey}'s own identical suppression already documents for this same constructor
 * parameter.
 */
@SuppressWarnings("PMD.ShortVariable")
public final class PlatformSigningKey extends AbstractSigningKey {

  private PlatformSigningKey(
      final UUID id,
      final String kid,
      final String algorithm,
      final Instant activeFrom,
      final Instant retiredAt) {
    super(id, kid, algorithm, activeFrom, retiredAt);
  }

  public static PlatformSigningKey activate(final String kid, final String algorithm) {
    return new PlatformSigningKey(UUID.randomUUID(), kid, algorithm, Instant.now(), null);
  }

  public static PlatformSigningKey reconstitute(
      final UUID id,
      final String kid,
      final String algorithm,
      final Instant activeFrom,
      final Instant retiredAt) {
    return new PlatformSigningKey(id, kid, algorithm, activeFrom, retiredAt);
  }
}
