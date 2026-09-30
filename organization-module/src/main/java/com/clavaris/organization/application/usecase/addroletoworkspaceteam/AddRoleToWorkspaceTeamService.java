package com.clavaris.organization.application.usecase.addroletoworkspaceteam;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link AddRoleToWorkspaceTeamUseCase}. ADR-0028 §2: a role may belong to at
 * most one team per Workspace — enforced here (the application layer), not the database, since
 * {@code workspace_team_roles} alone can't express "at most one team within this Workspace" without
 * also carrying {@code workspace_id} redundantly.
 *
 * <p>TD-ARCH-026 (closed): the check-then-insert below ({@link
 * WorkspaceTeamRepository#findTeamIdForRoleInWorkspace} then {@link
 * WorkspaceTeamRepository#addRoleToTeam}) is now serialized by {@link
 * WorkspaceTeamRepository#lockForTeamRoleChange} — see that method's own Javadoc for the race it
 * closes. {@code @Transactional} is load-bearing here, not incidental: {@code
 * pg_advisory_xact_lock} is transaction-scoped and auto-releases at commit/rollback, so without a
 * real transaction spanning the lock and the insert, the lock would release almost immediately and
 * serialize nothing.
 */
public class AddRoleToWorkspaceTeamService implements AddRoleToWorkspaceTeamUseCase {

  private final WorkspaceTeamRepository teams;
  private final WorkspaceRepository workspaces;
  private final WorkspaceRoleRepository roles;
  private final AuditEventRecorder auditEvents;

  public AddRoleToWorkspaceTeamService(
      final WorkspaceTeamRepository teams,
      final WorkspaceRepository workspaces,
      final WorkspaceRoleRepository roles,
      final AuditEventRecorder auditEvents) {
    this.teams = teams;
    this.workspaces = workspaces;
    this.roles = roles;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public void handle(final AddRoleToWorkspaceTeamCommand command) {
    final WorkspaceTeam team =
        teams
            .findById(command.teamId())
            .filter(candidate -> candidate.workspaceId().equals(command.workspaceId()))
            .orElseThrow(() -> new WorkspaceTeamNotFoundException(command.teamId()));

    final Workspace workspace =
        workspaces
            .findById(team.workspaceId())
            .orElseThrow(() -> new WorkspaceNotFoundException(team.workspaceId()));

    final WorkspaceRole role =
        roles
            .findById(command.roleId())
            .filter(candidate -> candidate.organizationId().equals(workspace.organizationId()))
            .orElseThrow(() -> new WorkspaceRoleNotFoundException(command.roleId()));

    // TD-ARCH-026: serializes every concurrent attempt to group this exact (workspaceId, roleId)
    // pair before the check below is even read — see WorkspaceTeamRepository#lockForTeamRoleChange
    // for the race this closes.
    teams.lockForTeamRoleChange(team.workspaceId(), role.id());
    final Optional<UUID> existingTeamId =
        teams.findTeamIdForRoleInWorkspace(team.workspaceId(), role.id());
    if (existingTeamId.isPresent()) {
      if (existingTeamId.get().equals(command.teamId())) {
        return; // Already grouped here — idempotent no-op.
      }
      throw new WorkspaceRoleAlreadyInAnotherTeamException(role.id(), existingTeamId.get());
    }

    teams.addRoleToTeam(command.teamId(), role.id());

    auditEvents.write(
        command.actor(),
        "workspace_team.role_added",
        "WorkspaceTeam",
        team.id().toString(),
        "roleId=" + role.id());
  }
}
