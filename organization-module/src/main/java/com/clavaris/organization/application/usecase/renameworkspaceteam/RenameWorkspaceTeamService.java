package com.clavaris.organization.application.usecase.renameworkspaceteam;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createworkspaceteam.DuplicateWorkspaceTeamNameException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link RenameWorkspaceTeamUseCase}.
 *
 * <p>TD-ARCH-027 (closed): {@code @Transactional} below — same rationale
 * CreateWorkspaceRoleService's own identical fix documents.
 */
public class RenameWorkspaceTeamService implements RenameWorkspaceTeamUseCase {

  private final WorkspaceTeamRepository teams;
  private final AuditEventRecorder auditEvents;

  public RenameWorkspaceTeamService(
      final WorkspaceTeamRepository teams, final AuditEventRecorder auditEvents) {
    this.teams = teams;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public WorkspaceTeam handle(final RenameWorkspaceTeamCommand command) {
    final WorkspaceTeam existing =
        teams
            .findById(command.teamId())
            .filter(candidate -> candidate.workspaceId().equals(command.workspaceId()))
            .orElseThrow(() -> new WorkspaceTeamNotFoundException(command.teamId()));

    final boolean nameChanged = !existing.name().equals(command.newName());
    final boolean nameTaken =
        teams.findAllByWorkspaceId(existing.workspaceId()).stream()
            .anyMatch(
                team ->
                    !team.id().equals(command.teamId()) && team.name().equals(command.newName()));
    if (nameChanged && nameTaken) {
      throw new DuplicateWorkspaceTeamNameException(command.newName());
    }

    final WorkspaceTeam renamed = existing.withName(command.newName());
    try {
      // TD-SEC-060: saveAndFlush, not save — see CreateWorkspaceRoleService's own identical fix
      // for why the ux_workspace_teams_workspace_id_name violation must surface synchronously here.
      teams.saveAndFlush(renamed);
    } catch (final DataIntegrityViolationException raceLost) {
      throw new DuplicateWorkspaceTeamNameException(command.newName(), raceLost);
    }

    auditEvents.write(
        command.actor(),
        "workspace_team.renamed",
        "WorkspaceTeam",
        renamed.id().toString(),
        "name=" + renamed.name());

    return renamed;
  }
}
