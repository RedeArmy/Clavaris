package com.clavaris.organization.application.usecase.deleteworkspacerole;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * @param organizationId TD-SEC-056 (fixed): the caller's own already-ownership-verified
 *     Organization — {@link DeleteWorkspaceRoleService} rejects a {@code roleId} whose real {@code
 *     organizationId} doesn't match it, the same anti-enumeration check {@code
 *     DeleteWorkspaceTeamCommand}'s own {@code workspaceId} already established for an identical
 *     gap. Previously absent entirely: {@code WorkspaceRolesController} (REST) accepted {@code
 *     organizationId} as a path variable but never threaded it any further, so any caller who knew
 *     (or guessed) a {@code roleId} could mutate or delete a role belonging to a completely
 *     different Organization by simply putting the "right" id in the URL — the path segment was
 *     purely decorative.
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
    UUID roleId, UUID organizationId, UUID workspaceId, boolean force, AuditActor actor) {

  public DeleteWorkspaceRoleCommand(
      final UUID roleId,
      final UUID organizationId,
      final UUID workspaceId,
      final AuditActor actor) {
    this(roleId, organizationId, workspaceId, false, actor);
  }

  public DeleteWorkspaceRoleCommand(
      final UUID roleId, final UUID organizationId, final AuditActor actor) {
    this(roleId, organizationId, null, false, actor);
  }
}
