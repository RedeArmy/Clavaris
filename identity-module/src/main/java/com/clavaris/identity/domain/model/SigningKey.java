package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * BR-ORG-04: every {@code Organization} owns its own RS256 signing key pair — metadata-only here,
 * same split as {@link PlatformSigningKey}: the real key material never lives in this table
 * (data-model.md §2), only in {@code
 * infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory}. Structurally separate
 * from {@link PlatformSigningKey} on purpose (ADR-0010, Organization provisioning) — a
 * platform-tier key and a tenant's own key must never be confusable with each other.
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
public final class SigningKey {

  private final UUID id;
  private final OrganizationId organizationId;
  private final String kid;
  private final String algorithm;
  private final Instant activeFrom;
  private Instant retiredAt;

  private SigningKey(
      final UUID id,
      final OrganizationId organizationId,
      final String kid,
      final String algorithm,
      final Instant activeFrom,
      final Instant retiredAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.kid = Objects.requireNonNull(kid, "kid must not be null");
    this.algorithm = Objects.requireNonNull(algorithm, "algorithm must not be null");
    this.activeFrom = Objects.requireNonNull(activeFrom, "activeFrom must not be null");
    this.retiredAt = retiredAt;
  }

  // BR-ORG-06: called synchronously as part of Organization creation — an Organization that
  // exists but cannot yet issue a token is never an observable state.
  public static SigningKey activate(
      final OrganizationId organizationId, final String kid, final String algorithm) {
    return new SigningKey(UUID.randomUUID(), organizationId, kid, algorithm, Instant.now(), null);
  }

  public static SigningKey reconstitute(
      final UUID id,
      final OrganizationId organizationId,
      final String kid,
      final String algorithm,
      final Instant activeFrom,
      final Instant retiredAt) {
    return new SigningKey(id, organizationId, kid, algorithm, activeFrom, retiredAt);
  }

  /**
   * CLAUDE.md §6: retiring stops it signing new tokens; JWKS still serves it until every token
   * issued under it expires.
   */
  public void retire() {
    this.retiredAt = Instant.now();
  }

  public UUID id() {
    return id;
  }

  public OrganizationId organizationId() {
    return organizationId;
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
