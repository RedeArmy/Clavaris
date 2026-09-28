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
 *     anywhere" behavior, unchanged, via the 2-arg constructor below.
 */
public record DeleteWorkspaceRoleCommand(UUID roleId, UUID workspaceId, AuditActor actor) {

  public DeleteWorkspaceRoleCommand(final UUID roleId, final AuditActor actor) {
    this(roleId, null, actor);
  }
}
