package com.clavaris.organization.application.usecase.deleteworkspaceteam;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.application.usecase.deleteworkspacerole.CannotDeleteReservedWorkspaceRoleException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleCommand;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleHasChildRolesException;
import com.clavaris.organization.application.usecase.deleteworkspacerole.WorkspaceRoleStillAssignedException;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;
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
 * holders first — see its own Javadoc) decides whether the attempt actually succeeds. Any of its
 * guard exceptions (still assigned in a DIFFERENT Workspace, has child roles, is the reserved role
 * with no substitute, would leave zero {@code manage_members} holders here) is caught and treated
 * as "leave it ungrouped" — a role surviving is never a failure of THIS operation, which always
 * deletes the team regardless.
 */
public class DeleteWorkspaceTeamService implements DeleteWorkspaceTeamUseCase {

  private final WorkspaceTeamRepository teams;
  private final DeleteWorkspaceRoleUseCase deleteRole;
  private final AuditEventRecorder auditEvents;

  public DeleteWorkspaceTeamService(
      final WorkspaceTeamRepository teams,
      final DeleteWorkspaceRoleUseCase deleteRole,
      final AuditEventRecorder auditEvents) {
    this.teams = teams;
    this.deleteRole = deleteRole;
    this.auditEvents = auditEvents;
  }

  // PMD.LongVariable: formerlyGroupedRoleIds names exactly what it is — same convention this
  // codebase's other descriptively-named variables already follow.
  @SuppressWarnings("PMD.LongVariable")
  @Override
  @Transactional
  public void handle(final DeleteWorkspaceTeamCommand command) {
    final WorkspaceTeam team =
        teams
            .findById(command.teamId())
            .filter(candidate -> candidate.workspaceId().equals(command.workspaceId()))
            .orElseThrow(() -> new WorkspaceTeamNotFoundException(command.teamId()));

    final List<UUID> formerlyGroupedRoleIds = teams.findRoleIdsByTeamId(command.teamId());

    teams.deleteById(command.teamId());

    auditEvents.write(
        command.actor(),
        "workspace_team.deleted",
        "WorkspaceTeam",
        team.id().toString(),
        "workspaceId=" + team.workspaceId());

    for (final UUID roleId : formerlyGroupedRoleIds) {
      deleteRoleIfNowOrphaned(command, roleId);
    }
  }

  // PMD.EmptyCatchBlock: deliberate — see this class's own Javadoc for why none of these guard
  // exceptions should abort the team deletion itself; the role is simply left ungrouped, same
  // "leave it as-is" outcome this codebase's other genuinely-intentional empty catches already
  // establish (e.g. SupabaseS3ProfilePictureStorage's own identical suppression).
  @SuppressWarnings("PMD.EmptyCatchBlock")
  private void deleteRoleIfNowOrphaned(
      final DeleteWorkspaceTeamCommand command, final UUID roleId) {
    if (teams.isRoleGroupedInAnyOtherTeam(roleId, command.teamId())) {
      return;
    }
    try {
      deleteRole.handle(
          new DeleteWorkspaceRoleCommand(roleId, command.workspaceId(), command.actor()));
    } catch (final WorkspaceRoleStillAssignedException
        | WorkspaceRoleHasChildRolesException
        | CannotDeleteReservedWorkspaceRoleException
        | CannotDemoteLastAdminException _) {
      // Left ungrouped, same as today — see this class's own Javadoc for why none of these abort
      // the team deletion itself.
    }
  }
}
