package com.clavaris.organization.application.usecase.deleteworkspacerole;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * @param workspaceId Live UX request, 2026-09-28: optional — when present, {@link
 *     DeleteWorkspaceRoleService} first auto-unassigns every member holding this role WITHIN this
 *     one Workspace before checking whether it's still assigned anywhere (a role also held in a
 *     DIFFERENT Workspace of the same Organization still blocks deletion, unchanged — see that
 *     service's own Javadoc). {@code null} for the two callers with no Workspace context at all
 *     ({@code WorkspaceRolesController}'s REST API, {@code PlatformWorkspaceRoleController}'s own
 *     Configure &gt; Workspace Roles page) — deletion there keeps today's strict "block if assigned
 *     anywhere" behavior, unchanged, via the 3-arg constructor below.
 * @param force Live UX request, 2026-09-29: {@code false} by default — when {@code true}, skips
 *     {@code ManageMembersGuard}'s "at least one manage_members holder must remain" check while
 *     auto-unassigning this role's holders within {@code workspaceId}. Same "dashboard-only, never
 *     REST-settable" posture {@link
 *     com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleCommand#force()}
 *     documents in full — {@code WorkspaceRolesController} (REST) always uses a constructor that
 *     defaults this to {@code false}. Does NOT affect {@code WorkspaceRoleStillAssignedException}
 *     (a DIFFERENT Workspace's own data), {@code WorkspaceRoleHasChildRolesException} (a real FK
 *     invariant), or {@code CannotDeleteReservedWorkspaceRoleException} (the Organization's own
 *     governance root) — those three stay hard blocks regardless of this flag; only the "would
 *     strip the last manage_members holder in THIS Workspace" case is ever forceable.
 */
public record DeleteWorkspaceRoleCommand(
    UUID roleId, UUID workspaceId, boolean force, AuditActor actor) {

  public DeleteWorkspaceRoleCommand(
      final UUID roleId, final UUID workspaceId, final AuditActor actor) {
    this(roleId, workspaceId, false, actor);
  }

  public DeleteWorkspaceRoleCommand(final UUID roleId, final AuditActor actor) {
    this(roleId, null, false, actor);
  }
}
