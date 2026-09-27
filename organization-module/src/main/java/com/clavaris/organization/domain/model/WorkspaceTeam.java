package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0028: a named, {@code Workspace}-scoped grouping label over a subset of that Workspace's own
 * Organization's {@code WorkspaceRole}s — purely organizational, opaque to Clavaris (same posture
 * {@code WorkspaceRole.name()} already holds), and carries no permission semantics of its own. A
 * role's membership in a team is a separate join row ({@code WorkspaceTeamRole}), never a field
 * here or on {@link WorkspaceRole} — see this ADR's own §2 for why: {@code WorkspaceRole} is
 * Organization-scoped (shared across every Workspace an Organization owns), while a {@code
 * WorkspaceTeam} belongs to exactly one Workspace, so "which team a role is grouped under" can only
 * be answered per-Workspace, never as a single value on the role itself.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class WorkspaceTeam {

  // Matches the workspace_teams.name column — same enforce-it-in-the-domain-too discipline every
  // other named entity in this module already establishes.
  private static final int MAX_NAME_LENGTH = 255;

  private final UUID id;
  private final UUID workspaceId;
  private final String name;
  private final Instant createdAt;

  private WorkspaceTeam(
      final UUID id, final UUID workspaceId, final String name, final Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    this.name = requireValidName(name);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  public static WorkspaceTeam define(final UUID workspaceId, final String name) {
    return new WorkspaceTeam(UUID.randomUUID(), workspaceId, name, Instant.now());
  }

  public static WorkspaceTeam reconstitute(
      final UUID id, final UUID workspaceId, final String name, final Instant createdAt) {
    return new WorkspaceTeam(id, workspaceId, name, createdAt);
  }

  /** {@code id}/{@code workspaceId}/{@code createdAt} never change. */
  public WorkspaceTeam withName(final String newName) {
    return new WorkspaceTeam(id, workspaceId, newName, createdAt);
  }

  private static String requireValidName(final String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("WorkspaceTeam name must not be blank");
    }
    if (name.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "WorkspaceTeam name must not exceed " + MAX_NAME_LENGTH + " characters");
    }
    return name;
  }

  public UUID id() {
    return id;
  }

  public UUID workspaceId() {
    return workspaceId;
  }

  public String name() {
    return name;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
