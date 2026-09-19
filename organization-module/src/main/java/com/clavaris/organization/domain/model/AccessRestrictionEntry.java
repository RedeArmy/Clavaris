package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * SDE-III review, 2026-09-19 — Clerk "Restrictions" parity, minimal: one entry in one {@code
 * Organization}'s own blocklist or allowlist. {@code identifier} is either a full email address
 * ({@code "user@example.com"}, exact match) or a bare domain prefixed with {@code "@"} ({@code
 * "@example.com"}, matches any email at that domain) — see {@link
 * com.clavaris.organization.domain.service.AccessRestrictionPolicy} for the matching logic itself,
 * kept out of this class so the aggregate stays a plain value holder, same split {@link
 * RateLimitPolicy} already establishes between "what a policy is" and "how it's evaluated."
 *
 * <p>Normalized (trimmed, lower-cased) at construction — same reasoning {@code Email}
 * (identity-module) already documents for itself: case/whitespace variation of the same identifier
 * must never defeat the {@code UNIQUE(organization_id, identifier)} constraint or let an entry
 * silently fail to match a real login attempt.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class AccessRestrictionEntry {

  private final UUID id;
  private final UUID organizationId;
  private final RestrictionType type;
  private final String identifier;
  private final Instant createdAt;

  private AccessRestrictionEntry(
      final UUID id,
      final UUID organizationId,
      final RestrictionType type,
      final String identifier,
      final Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.identifier =
        Objects.requireNonNull(identifier, "identifier must not be null")
            .trim()
            .toLowerCase(Locale.ROOT);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    if (this.identifier.isEmpty()) {
      throw new IllegalArgumentException("identifier must not be blank");
    }
  }

  public static AccessRestrictionEntry create(
      final UUID organizationId, final RestrictionType type, final String identifier) {
    return new AccessRestrictionEntry(
        UUID.randomUUID(), organizationId, type, identifier, Instant.now());
  }

  public static AccessRestrictionEntry reconstitute(
      final UUID id,
      final UUID organizationId,
      final RestrictionType type,
      final String identifier,
      final Instant createdAt) {
    return new AccessRestrictionEntry(id, organizationId, type, identifier, createdAt);
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public RestrictionType type() {
    return type;
  }

  public String identifier() {
    return identifier;
  }

  public Instant createdAt() {
    return createdAt;
  }

  /**
   * {@code "@example.com"}-shaped entries match any email at that domain, not one exact address.
   */
  public boolean isDomainPattern() {
    return identifier.startsWith("@");
  }
}
