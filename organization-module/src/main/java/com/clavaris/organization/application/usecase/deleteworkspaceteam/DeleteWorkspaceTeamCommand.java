package com.clavaris.organization.application.usecase.deleteworkspaceteam;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * SDE-III review, 2026-09-27 (real IDOR found and closed): {@code workspaceId} is the caller's own
 * already-ownership-verified Workspace — {@link DeleteWorkspaceTeamService} rejects a {@code
 * teamId} whose real {@code workspaceId} doesn't match it, the same anti-enumeration check every
 * other cross-Organization-reachable resource in this codebase already applies. Without it, any
 * caller who owns any Organization could delete any other Organization's own team by guessing its
 * id — the controller's own ownership checks never reached this far down.
 *
 * @param organizationId TD-SEC-056 follow-up, 2026-09-29: the caller's own already-verified
 *     Organization, threaded into the nested {@code DeleteWorkspaceRoleCommand} this service's own
 *     cascade constructs for every role this team's own deletion orphans — {@link
 *     DeleteWorkspaceRoleCommand#organizationId()}'s own anti-enumeration check would otherwise
 *     have nothing to compare against for that internal call.
 * @param force Live UX request, 2026-09-29: {@code false} by default (the 3-arg-plus-actor
 *     constructor below). {@code false}: if deleting this team would orphan a role that's the last
 *     {@code manage_members}/{@code manage_roles} holder in this Workspace, the WHOLE operation
 *     aborts (the team is NOT deleted either — {@link DeleteWorkspaceTeamService#handle} is one
 *     {@code @Transactional} method) and {@code CannotDemoteLastAdminException} propagates to the
 *     caller, same as today's behavior for every other exception this cascade doesn't silently
 *     swallow. {@code true}: that specific check is skipped — the orphaned role's last holder is
 *     unassigned and the role is deleted along with the team, no abort. No REST caller exists for
 *     this use case today, but the same "dashboard-only" posture {@code
 *     ChangeWorkspaceMemberRoleCommand#force()} documents still applies if one is ever added.
 */
public record DeleteWorkspaceTeamCommand(
    UUID organizationId, UUID workspaceId, UUID teamId, boolean force, AuditActor actor) {

  public DeleteWorkspaceTeamCommand(
      final UUID organizationId,
      final UUID workspaceId,
      final UUID teamId,
      final AuditActor actor) {
    this(organizationId, workspaceId, teamId, false, actor);
  }
}
