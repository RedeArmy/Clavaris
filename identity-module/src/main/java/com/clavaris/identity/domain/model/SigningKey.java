package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BR-ORG-04: every {@code Organization} owns its own RS256 signing key pair — metadata-only here,
 * same split as {@link PlatformSigningKey}: the real key material never lives in this table
 * (data-model.md §2), only in {@code
 * infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory}. Structurally separate
 * from {@link PlatformSigningKey} on purpose (ADR-0010, Organization provisioning) — a
 * platform-tier key and a tenant's own key must never be confusable with each other.
 *
 * <p>Shared state/lifecycle (everything except {@link #organizationId()}) lives on {@link
 * AbstractSigningKey} — see its own Javadoc for why this pair shares a base without a generic
 * owning-id type parameter (TD-ARCH-009).
 *
 * <p>PMD's AvoidFieldNameMatchingMethodName/ShortVariable rules flag this class for the same reason
 * {@link Account} suppresses them — the deliberate record-style accessor convention used throughout
 * this codebase's value objects.
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
