package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BR-WS-04/05: links an identity-module {@code Account} to one {@link Workspace}, with a nullable
 * {@link WorkspaceRole} reference (ADR-0027 — replaces the old fixed {@code ADMIN}/{@code MEMBER}
 * enum). {@code null} means "member of the Workspace, no role currently assigned" — an explicitly
 * allowed state (ADR-0027 §5), reached by unassigning a role rather than reassigning to another
 * one; a membership in this state carries no {@code workspace_permissions} until reassigned.
 *
 * <p>{@code accountId} is deliberately a plain {@link UUID}, never an identity-module type —
 * organization-module and identity-module stay mutually independent business modules (the hexagonal
 * dependency rule applied at the module-graph level, same convention {@link
 * Organization#ownerPlatformAccountId()} already follows for its own cross-module reference).
 * Because the referenced {@code Account} already belongs to this Workspace's own {@code
 * Organization} (every {@code Account} is provisioned by {@code AddWorkspaceMemberService} scoped
 * to that same Organization), membership is structurally confined to one tenant's account pool
 * without needing an extra cross-check.
 *
 * <p>Same record-style-accessor PMD suppressions as {@link Organization}/{@link Workspace}.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class WorkspaceMembership {

  private final UUID id;
  private final UUID workspaceId;
  private final UUID accountId;
  private final UUID roleId;
  private final Instant createdAt;

  private WorkspaceMembership(
      final UUID id,
      final UUID workspaceId,
      final UUID accountId,
      final UUID roleId,
      final Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.roleId = roleId;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  public static WorkspaceMembership join(
      final UUID workspaceId, final UUID accountId, final UUID roleId) {
    return new WorkspaceMembership(
        UUID.randomUUID(), workspaceId, accountId, roleId, Instant.now());
  }

  public static WorkspaceMembership reconstitute(
      final UUID id,
      final UUID workspaceId,
      final UUID accountId,
      final UUID roleId,
      final Instant createdAt) {
    return new WorkspaceMembership(id, workspaceId, accountId, roleId, createdAt);
  }

  /**
   * A same-shaped copy with a different (or {@code null}, i.e. unassigned) {@code roleId} — {@code
   * id}/{@code workspaceId}/{@code accountId}/{@code createdAt} are all immutable (a role change is
   * not a new membership).
   */
  public WorkspaceMembership withRoleId(final UUID newRoleId) {
    return new WorkspaceMembership(id, workspaceId, accountId, newRoleId, createdAt);
  }

  public UUID id() {
    return id;
  }

  public UUID workspaceId() {
    return workspaceId;
  }

  public UUID accountId() {
    return accountId;
  }

  public UUID roleId() {
    return roleId;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
