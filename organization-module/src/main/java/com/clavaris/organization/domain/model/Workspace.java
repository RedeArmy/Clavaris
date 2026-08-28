package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0010 §3: a team/company grouping *within* one {@code Organization}'s isolated account pool —
 * not the tenant-isolation boundary itself (that's {@link Organization}). {@code organizationId} is
 * mandatory and immutable; a {@code Workspace} can never span two Organizations.
 *
 * <p>Same record-style-accessor PMD suppressions as {@link Organization}, same rationale — the
 * deliberate convention this codebase's domain models use throughout, not an accidental data-holder
 * shape.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class Workspace {

  // Matches the workspaces.name column (varchar(255), V20260827__create_workspaces_table
  // migration) — same enforce-it-in-the-domain-too discipline Organization already established
  // (SDE-III review, 2026-08-22): a web-layer @Size(max = 255) alone leaves any non-web caller with
  // no check at all.
  private static final int MAX_NAME_LENGTH = 255;

  private final UUID id;
  private final UUID organizationId;
  private final String name;
  private final Instant createdAt;

  private Workspace(
      final UUID id, final UUID organizationId, final String name, final Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.name = requireValidName(name);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  public static Workspace register(final UUID organizationId, final String name) {
    return new Workspace(UUID.randomUUID(), organizationId, name, Instant.now());
  }

  public static Workspace reconstitute(
      final UUID id, final UUID organizationId, final String name, final Instant createdAt) {
    return new Workspace(id, organizationId, name, createdAt);
  }

  private static String requireValidName(final String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Workspace name must not be blank");
    }
    if (name.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "Workspace name must not exceed " + MAX_NAME_LENGTH + " characters");
    }
    return name;
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public String name() {
    return name;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
