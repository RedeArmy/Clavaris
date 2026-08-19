package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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
 * <p>PMD's AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this class for
 * the same reason {@link Account} suppresses them — the deliberate record-style accessor convention
 * used throughout this codebase's value objects, not an accidental data-holder shape.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class PlatformSigningKey {

  private final UUID id;
  private final String kid;
  private final String algorithm;
  private final Instant activeFrom;
  private Instant retiredAt;

  private PlatformSigningKey(
      final UUID id,
      final String kid,
      final String algorithm,
      final Instant activeFrom,
      final Instant retiredAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.kid = Objects.requireNonNull(kid, "kid must not be null");
    this.algorithm = Objects.requireNonNull(algorithm, "algorithm must not be null");
    this.activeFrom = Objects.requireNonNull(activeFrom, "activeFrom must not be null");
    this.retiredAt = retiredAt;
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

  /**
   * CLAUDE.md §6: JWKS always exposes the previous key until every token signed under it has
   * expired — retiring stops it signing new tokens, doesn't remove the row.
   */
  public void retire() {
    this.retiredAt = Instant.now();
  }

  public UUID id() {
    return id;
  }

  public String kid() {
    return kid;
  }

  public String algorithm() {
    return algorithm;
  }

  public Instant activeFrom() {
    return activeFrom;
  }

  public Optional<Instant> retiredAt() {
    return Optional.ofNullable(retiredAt);
  }
}
