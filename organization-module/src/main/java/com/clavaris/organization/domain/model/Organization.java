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
 * <p>PMD's AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this class for
 * the same reason {@code Account} (identity-module) suppresses them — the deliberate record-style
 * accessor convention used throughout this codebase's value objects, not an accidental data-holder
 * shape.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class Organization {

  private final UUID id;
  private final String name;
  private final Instant createdAt;

  private Organization(final UUID id, final String name, final Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.name = requireNonBlank(name);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  // BR-ORG-06: created by a Clavaris operator via POST /api/v1/admin/organizations — never a
  // self-service action in v1 (ADR-0010, Organization provisioning).
  public static Organization register(final String name) {
    return new Organization(UUID.randomUUID(), name, Instant.now());
  }

  public static Organization reconstitute(
      final UUID id, final String name, final Instant createdAt) {
    return new Organization(id, name, createdAt);
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
}
