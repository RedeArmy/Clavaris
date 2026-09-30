package com.clavaris.organization.application.usecase.removerolefromworkspaceteam;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link RemoveRoleFromWorkspaceTeamUseCase}. Idempotent — removing a role that
 * isn't actually in this team is not an error, same "the end state is what matters" reasoning a
 * plain {@code DELETE} on a non-existent row already gives for free.
 *
 * <p>TD-ARCH-027 (closed): {@code @Transactional} below — same rationale
 * CreateWorkspaceRoleService's own identical fix documents.
 */
public class RemoveRoleFromWorkspaceTeamService implements RemoveRoleFromWorkspaceTeamUseCase {

  private final WorkspaceTeamRepository teams;
  private final AuditEventRecorder auditEvents;

  public RemoveRoleFromWorkspaceTeamService(
      final WorkspaceTeamRepository teams, final AuditEventRecorder auditEvents) {
    this.teams = teams;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final RemoveRoleFromWorkspaceTeamCommand command) {
    final WorkspaceTeam team =
        teams
            .findById(command.teamId())
            .filter(candidate -> candidate.workspaceId().equals(command.workspaceId()))
            .orElseThrow(() -> new WorkspaceTeamNotFoundException(command.teamId()));

    teams.removeRoleFromTeam(command.teamId(), command.roleId());

    auditEvents.write(
        command.actor(),
        "workspace_team.role_removed",
        "WorkspaceTeam",
        team.id().toString(),
        "roleId=" + command.roleId());
  }
}
