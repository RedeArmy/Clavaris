package com.clavaris.organization.application.usecase.deleteworkspaceteam;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.domain.model.WorkspaceTeam;

/**
 * Orchestration for {@link DeleteWorkspaceTeamUseCase}. ADR-0028: deleting a team drops its own
 * role associations (cascaded at the database level, {@code workspace_team_roles.workspace_team_id
 * ON DELETE CASCADE}) — the roles themselves survive, becoming ungrouped in this Workspace again.
 * No membership/hierarchy invariant is affected: a team carries no permission semantics of its own,
 * unlike deleting a {@code WorkspaceRole} itself.
 */
public class DeleteWorkspaceTeamService implements DeleteWorkspaceTeamUseCase {

  private final WorkspaceTeamRepository teams;
  private final AuditEventRecorder auditEvents;

  public DeleteWorkspaceTeamService(
      final WorkspaceTeamRepository teams, final AuditEventRecorder auditEvents) {
    this.teams = teams;
    this.auditEvents = auditEvents;
  }

  @Override
  public void handle(final DeleteWorkspaceTeamCommand command) {
    final WorkspaceTeam team =
        teams
            .findById(command.teamId())
            .filter(candidate -> candidate.workspaceId().equals(command.workspaceId()))
            .orElseThrow(() -> new WorkspaceTeamNotFoundException(command.teamId()));

    teams.deleteById(command.teamId());

    auditEvents.write(
        command.actor(),
        "workspace_team.deleted",
        "WorkspaceTeam",
        team.id().toString(),
        "workspaceId=" + team.workspaceId());
  }
}
