package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0010: the tenant isolation boundary — one row per consuming system (e.g. "JobSeeker" is one
 * {@code Organization}), owning its own fully isolated pool of {@code Account}s and {@code
 * OAuthClient}s. Not to be confused with {@code Workspace}, a team/company grouping *within* one
 * Organization's account pool.
 *
 * <p>{@code ownerPlatformAccountId} (ADR-0012): exactly one owning {@code PlatformAccount} per
 * Organization — a plain {@link UUID}, not identity-module's own {@code PlatformAccountId} type,
 * since organization-module never depends on identity-module (same module-independence discipline
 * {@code SigningKeyProvisioner}'s own Javadoc documents for {@code SigningKey}).
 *
 * <p>PMD's AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this class for
 * the same reason {@code Account} (identity-module) suppresses them — the deliberate record-style
 * accessor convention used throughout this codebase's value objects, not an accidental data-holder
 * shape.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.LongVariable"
})
public final class Organization {

  private final UUID id;
  private final String name;
  private final Instant createdAt;
  private final UUID ownerPlatformAccountId;

  private Organization(
      final UUID id,
      final String name,
      final Instant createdAt,
      final UUID ownerPlatformAccountId) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.name = requireNonBlank(name);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.ownerPlatformAccountId =
        Objects.requireNonNull(ownerPlatformAccountId, "ownerPlatformAccountId must not be null");
  }

  // ADR-0012: created either by the owning PlatformAccount itself via the session-authenticated
  // dashboard, or by a Clavaris operator via POST /api/v1/admin/organizations (BR-ORG-06) on that
  // PlatformAccount's behalf — either path resolves to this same factory, ownerPlatformAccountId
  // is never optional.
  public static Organization register(final String name, final UUID ownerPlatformAccountId) {
    return new Organization(UUID.randomUUID(), name, Instant.now(), ownerPlatformAccountId);
  }

  public static Organization reconstitute(
      final UUID id,
      final String name,
      final Instant createdAt,
      final UUID ownerPlatformAccountId) {
    return new Organization(id, name, createdAt, ownerPlatformAccountId);
  }

  private static String requireNonBlank(final String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Organization name must not be blank");
    }
    return name;
  }

  public UUID id() {
    return id;
  }

  public String name() {
    return name;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public UUID ownerPlatformAccountId() {
    return ownerPlatformAccountId;
  }
}
