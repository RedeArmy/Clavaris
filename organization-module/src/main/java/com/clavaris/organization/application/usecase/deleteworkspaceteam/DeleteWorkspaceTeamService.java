package com.clavaris.organization.application.usecase.deleteworkspaceteam;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.ManageMembersGuard;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link DeleteWorkspaceTeamUseCase}. ADR-0028: deleting a team drops its own
 * role associations (cascaded at the database level, {@code workspace_team_roles.workspace_team_id
 * ON DELETE CASCADE}) — the roles themselves survive, becoming ungrouped in this Workspace, UNLESS
 * a role this team's own deletion just orphaned isn't grouped or assigned anywhere else at all, in
 * which case it's now deleted too (live UX request, 2026-09-28).
 *
 * <p>"Anywhere else" is checked two ways, composing {@link DeleteWorkspaceRoleUseCase} rather than
 * re-implementing its guards: {@link WorkspaceTeamRepository#isRoleGroupedInAnyOtherTeam} (org-wide
 * — a role may be grouped into a different team of a different Workspace of the same Organization,
 * ADR-0028 §2) decides whether an attempt is made at all; {@link DeleteWorkspaceRoleUseCase} itself
 * (given this team's own {@code workspaceId}, so it also auto-unassigns this Workspace's own
 * holders first — see its own Javadoc) decides whether the attempt actually succeeds.
 *
 * <p><b>Three of its four guard exceptions</b> (still assigned in a DIFFERENT Workspace, has child
 * roles, is the reserved role with no substitute) are caught and treated as "leave it ungrouped" —
 * a role surviving is never a failure of THIS operation on its own.
 *
 * <p><b>{@link CannotDemoteLastAdminException} is different, live UX request 2026-09-29:</b> unlike
 * the other three, this one is never silently swallowed — a violation aborts the WHOLE operation,
 * nothing persisted, not just the offending role's own deletion.
 *
 * <p><b>TD-ARCH-023 (fixed): why this can no longer rely on {@code DeleteWorkspaceRoleService}'s
 * own {@code REQUIRES_NEW} propagation to enforce that abort.</b> A team can orphan (and then
 * delete) more than one role in the same cascade. {@code REQUIRES_NEW} isolates each nested {@code
 * DeleteWorkspaceRoleService#handle} call in its own physical transaction — correct for the three
 * tolerated exceptions above (a caught, tolerated failure on one role must never poison this
 * method's own outer transaction), but wrong for this one: if role A's own {@code
 * ManageMembersGuard} check passes and its {@code REQUIRES_NEW} transaction commits, and only THEN
 * role B's own check trips {@link CannotDemoteLastAdminException}, this method's own transaction
 * rolls back (undoing the team row and its audit event) — but role A's already-committed deletion
 * cannot be un-committed retroactively. The team ends up still existing (matching what the caller
 * is told: "nothing was deleted, retry with force") while one of its own roles has permanently
 * vanished anyway, along with a {@code WorkspaceRoleDeletedEvent} already durably queued in {@code
 * event_outbox} and potentially already delivered to a webhook subscriber.
 *
 * <p>Fixed by checking the WHOLE cascade's combined impact ONCE, via {@link
 * ManageMembersGuard#assertUnassigningRolesKeepsAtLeastOneHolder}, before the team row or any role
 * in it is touched at all — an aggregate check across every role this cascade would actually
 * delete, not one check per role. Once that passes (or {@link DeleteWorkspaceTeamCommand#force()}
 * is set), every nested {@link DeleteWorkspaceRoleCommand} in this cascade is built with {@code
 * force=true} unconditionally: {@code CannotDemoteLastAdminException} can then never actually fire
 * during the second phase, so {@code REQUIRES_NEW}'s own "isolated, independently-committing"
 * behavior — still exactly right for the three tolerated exceptions — never gets a chance to leave
 * a partial cascade behind for this one.
 */
public class DeleteWorkspaceTeamService implements DeleteWorkspaceTeamUseCase {

  private final WorkspaceTeamRepository teams;
  private final DeleteWorkspaceRoleUseCase deleteRole;
  private final AuditEventRecorder auditEvents;
  private final WorkspaceMembershipRepository memberships;
  private final WorkspaceRoleRepository roles;

  // java:S107: five collaborating ports — TD-ARCH-023's own fix needed the two new ones
  // (memberships/roles) to run ManageMembersGuard's aggregate check itself, same "wiring together
  // what the guard needs" reasoning ManageMembersGuard's own callers already establish.
  @SuppressWarnings("java:S107")
  public DeleteWorkspaceTeamService(
      final WorkspaceTeamRepository teams,
      final DeleteWorkspaceRoleUseCase deleteRole,
      final AuditEventRecorder auditEvents,
      final WorkspaceMembershipRepository memberships,
      final WorkspaceRoleRepository roles) {
    this.teams = teams;
    this.deleteRole = deleteRole;
    this.auditEvents = auditEvents;
    this.memberships = memberships;
    this.roles = roles;
  }

  // PMD.LongVariable: formerlyGroupedRoleIds/rolesToActuallyDelete each name exactly what they are
  // — same convention this codebase's other descriptively-named variables already follow.
  @SuppressWarnings("PMD.LongVariable")
  @Override
  @Transactional
  public void handle(final DeleteWorkspaceTeamCommand command) {
    final WorkspaceTeam team =
        teams
            .findById(command.teamId())
            .filter(candidate -> candidate.workspaceId().equals(command.workspaceId()))
            .orElseThrow(() -> new WorkspaceTeamNotFoundException(command.teamId()));

    // Computed before anything is touched — isRoleGroupedInAnyOtherTeam queries
    // workspace_team_roles directly and never depends on this team's own row still existing, so
    // resolving the real deletion set up front (rather than per-role, after the team is already
    // gone) costs nothing and is what TD-ARCH-023's own pre-check needs to run against.
    final List<UUID> formerlyGroupedRoleIds = teams.findRoleIdsByTeamId(command.teamId());
    final List<UUID> rolesToActuallyDelete =
        formerlyGroupedRoleIds.stream()
            .filter(roleId -> !teams.isRoleGroupedInAnyOtherTeam(roleId, command.teamId()))
            .toList();

    if (!command.force() && !rolesToActuallyDelete.isEmpty()) {
      ManageMembersGuard.assertUnassigningRolesKeepsAtLeastOneHolder(
          memberships,
          roles,
          command.workspaceId(),
          command.organizationId(),
          Set.copyOf(rolesToActuallyDelete),
          () -> new CannotDemoteLastAdminException(command.workspaceId()));
    }

    teams.deleteById(command.teamId());

    auditEvents.write(
        command.actor(),
        "workspace_team.deleted",
        "WorkspaceTeam",
        team.id().toString(),
        "workspaceId=" + team.workspaceId());

    for (final UUID roleId : rolesToActuallyDelete) {
      deleteAlreadyValidatedRole(command, roleId);
    }
  }

  // PMD.EmptyCatchBlock: deliberate — see this class's own Javadoc for why these three (and only
  // these three) guard exceptions should never abort the team deletion itself; the role is simply
  // left ungrouped, same "leave it as-is" outcome this codebase's other genuinely-intentional
  // empty catches already establish (e.g. SupabaseS3ProfilePictureStorage's own identical
  // suppression). CannotDemoteLastAdminException can never fire here — the aggregate check in
  // handle() already ruled it out (or the caller explicitly forced it) before this loop started.
  @SuppressWarnings("PMD.EmptyCatchBlock")
  private void deleteAlreadyValidatedRole(
      final DeleteWorkspaceTeamCommand command, final UUID roleId) {
    try {
      deleteRole.handle(
          new DeleteWorkspaceRoleCommand(
              roleId, command.organizationId(), command.workspaceId(), true, command.actor()));
    } catch (final WorkspaceRoleStillAssignedException
        | WorkspaceRoleHasChildRolesException
        | CannotDeleteReservedWorkspaceRoleException _) {
      // Left ungrouped, same as today — see this class's own Javadoc for why these three never
      // abort the team deletion itself.
    }
  }
}
