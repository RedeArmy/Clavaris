package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BR-ORG-04: every {@code Organization} owns its own RS256 key pair — metadata-only here, same
 * split as {@link PlatformSigningKey}; the real key material lives in {@code
 * OrganizationSigningKeyMaterialFactory} (data-model.md §2). Structurally separate from {@link
 * PlatformSigningKey} (ADR-0010) — a platform-tier key and a tenant's own must never be confusable.
 *
 * <p>Shared state/lifecycle lives on {@link AbstractSigningKey} (TD-ARCH-009). PMD suppressions
 * below: coding-standards.md §3a.
 */
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortVariable"})
public final class SigningKey extends AbstractSigningKey {

  private final OrganizationId organizationId;

  private SigningKey(
      final UUID id,
      final OrganizationId organizationId,
      final String kid,
      final String algorithm,
      final Instant activeFrom,
      final Instant retiredAt) {
    super(id, kid, algorithm, activeFrom, retiredAt);
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
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

  public OrganizationId organizationId() {
    return organizationId;
  }
}
