package com.clavaris.organization.application.usecase.createworkspaceteam;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link CreateWorkspaceTeamUseCase}.
 *
 * <p>TD-ARCH-027 (closed): {@code @Transactional} below — same rationale
 * CreateWorkspaceRoleService's own identical fix documents.
 */
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
  @Transactional
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
    try {
      // TD-SEC-060: saveAndFlush, not save — see CreateWorkspaceRoleService's own identical fix
      // for why the ux_workspace_teams_workspace_id_name violation must surface synchronously here.
      teams.saveAndFlush(team);
    } catch (final DataIntegrityViolationException raceLost) {
      throw new DuplicateWorkspaceTeamNameException(command.name(), raceLost);
    }

    auditEvents.write(
        command.actor(),
        "workspace_team.created",
        "WorkspaceTeam",
        team.id().toString(),
        "workspaceId=" + team.workspaceId() + " name=" + team.name());

    return team;
  }
}
