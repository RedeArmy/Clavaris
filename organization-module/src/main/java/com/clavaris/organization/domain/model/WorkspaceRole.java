package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * ADR-0027: replaces the old {@code ADMIN}/{@code MEMBER} enum (ADR-0010 §3 addendum, now
 * superseded). A {@code WorkspaceRole} is defined once per {@link Organization} — shared across
 * every {@link Workspace} that Organization owns — and is a named bundle of opaque,
 * consumer-defined {@code permissions} strings: Clavaris never interprets what a permission or a
 * {@code name} means, the same posture {@code OAuthClient.allowedScopes}/{@code
 * WebhookEndpoint.subscribedEventTypes} already hold for an identical reason (ADR-0027 §1's own
 * "opaque string, generic infrastructure" discipline).
 *
 * <p>{@code reserved} is {@code true} only for the one system-seeded bootstrap role per
 * Organization ({@link CreateWorkspaceService}, ADR-0027 §2) — it can never be stripped of {@link
 * ReservedWorkspacePermissions#ALL} (enforced by {@link #withPermissions}); whether it can be
 * deleted at all is enforced one layer up, by the use case that deletes a {@code WorkspaceRole},
 * not here (this class has no notion of "delete").
 *
 * <p>{@code parentRoleId} is an optional, single-hop self-reference (ADR-0027 §3) — only a
 * same-role self-parent is rejected here; a multi-hop cycle across several roles needs the full
 * per-Organization role graph, which is {@link
 * com.clavaris.organization.domain.service.WorkspaceRoleHierarchy}'s own job, not this class's.
 *
 * <p>Same record-style-accessor PMD suppressions as {@link Workspace}/{@link Organization}.
 */
// PMD.TooManyMethods: every method here is a real, distinct accessor or immutable-copy-with
// mutator this entity's own callers actually need (same shape Workspace/Organization already
// establish, just with more fields to expose) — not a design smell to split up.
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods"
})
public final class WorkspaceRole {

  // Matches the workspace_roles.name column (varchar(255)) — same enforce-it-in-the-domain-too
  // discipline Workspace/Organization already establish for their own name fields.
  private static final int MAX_NAME_LENGTH = 255;

  private final UUID id;
  private final UUID organizationId;
  private final String name;
  private final UUID parentRoleId;
  private final Set<String> permissions;
  private final boolean reserved;
  private final Instant createdAt;

  private WorkspaceRole(
      final UUID id,
      final UUID organizationId,
      final String name,
      final UUID parentRoleId,
      final Set<String> permissions,
      final boolean reserved,
      final Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.name = requireValidName(name);
    if (parentRoleId != null && parentRoleId.equals(id)) {
      throw new IllegalArgumentException("A WorkspaceRole cannot be its own parent");
    }
    this.parentRoleId = parentRoleId;
    this.permissions =
        Set.copyOf(Objects.requireNonNull(permissions, "permissions must not be null"));
    if (reserved && !this.permissions.containsAll(ReservedWorkspacePermissions.ALL)) {
      throw new IllegalArgumentException(
          "A reserved WorkspaceRole must carry every ReservedWorkspacePermissions value");
    }
    this.reserved = reserved;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  public static WorkspaceRole define(
      final UUID organizationId,
      final String name,
      final UUID parentRoleId,
      final Set<String> permissions) {
    return new WorkspaceRole(
        UUID.randomUUID(), organizationId, name, parentRoleId, permissions, false, Instant.now());
  }

  /** ADR-0027 §2 — the one system-seeded, per-Organization bootstrap role. */
  public static WorkspaceRole defineReserved(final UUID organizationId, final String name) {
    return new WorkspaceRole(
        UUID.randomUUID(),
        organizationId,
        name,
        null,
        ReservedWorkspacePermissions.ALL,
        true,
        Instant.now());
  }

  public static WorkspaceRole reconstitute(
      final UUID id,
      final UUID organizationId,
      final String name,
      final UUID parentRoleId,
      final Set<String> permissions,
      final boolean reserved,
      final Instant createdAt) {
    return new WorkspaceRole(
        id, organizationId, name, parentRoleId, permissions, reserved, createdAt);
  }

  private static String requireValidName(final String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("WorkspaceRole name must not be blank");
    }
    if (name.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "WorkspaceRole name must not exceed " + MAX_NAME_LENGTH + " characters");
    }
    return name;
  }

  /** {@code id}/{@code organizationId}/{@code reserved}/{@code createdAt} never change. */
  public WorkspaceRole withName(final String newName) {
    return new WorkspaceRole(
        id, organizationId, newName, parentRoleId, permissions, reserved, createdAt);
  }

  /**
   * Rejects stripping {@link ReservedWorkspacePermissions#ALL} from a {@code reserved} role — see
   * this class's own Javadoc.
   */
  public WorkspaceRole withPermissions(final Set<String> newPermissions) {
    return new WorkspaceRole(
        id, organizationId, name, parentRoleId, newPermissions, reserved, createdAt);
  }

  /** Single-hop self-parent check only — see this class's own Javadoc for the multi-hop case. */
  public WorkspaceRole withParentRoleId(final UUID newParentRoleId) {
    return new WorkspaceRole(
        id, organizationId, name, newParentRoleId, permissions, reserved, createdAt);
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

  public UUID parentRoleId() {
    return parentRoleId;
  }

  public Set<String> permissions() {
    return permissions;
  }

  public boolean reserved() {
    return reserved;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
