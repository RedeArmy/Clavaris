package com.clavaris.organization.application.usecase.createworkspaceteam;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.WorkspaceTeam;

/** Orchestration for {@link CreateWorkspaceTeamUseCase}. */
public class CreateWorkspaceTeamService implements CreateWorkspaceTeamUseCase {

  private final WorkspaceTeamRepository teams;
  private final WorkspaceRepository workspaces;
  private final AuditEventRecorder auditEvents;

  public CreateWorkspaceTeamService(
      final WorkspaceTeamRepository teams,
      final WorkspaceRepository workspaces,
      final AuditEventRecorder auditEvents) {
    this.teams = teams;
    this.workspaces = workspaces;
    this.auditEvents = auditEvents;
  }

  @Override
  public WorkspaceTeam handle(final CreateWorkspaceTeamCommand command) {
    if (workspaces.findById(command.workspaceId()).isEmpty()) {
      throw new WorkspaceNotFoundException(command.workspaceId());
    }

    final boolean nameTaken =
        teams.findAllByWorkspaceId(command.workspaceId()).stream()
            .anyMatch(team -> team.name().equals(command.name()));
    if (nameTaken) {
      throw new DuplicateWorkspaceTeamNameException(command.name());
    }

    final WorkspaceTeam team = WorkspaceTeam.define(command.workspaceId(), command.name());
    teams.save(team);

    auditEvents.write(
        command.actor(),
        "workspace_team.created",
        "WorkspaceTeam",
        team.id().toString(),
        "workspaceId=" + team.workspaceId() + " name=" + team.name());

    return team;
  }
}
