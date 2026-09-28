package com.clavaris.organization.application.usecase.deleteworkspaceteam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeleteWorkspaceTeamServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceTeamRepository teams;
  private DeleteWorkspaceRoleUseCase deleteRole;
  private AuditEventRecorder auditEvents;
  private DeleteWorkspaceTeamService service;

  private WorkspaceTeam team;

  @BeforeEach
  void setUp() {
    teams = mock(WorkspaceTeamRepository.class);
    deleteRole = mock(DeleteWorkspaceRoleUseCase.class);
    auditEvents = mock(AuditEventRecorder.class);
    team = WorkspaceTeam.define(UUID.randomUUID(), "QA");
    when(teams.findById(team.id())).thenReturn(Optional.of(team));
    service = new DeleteWorkspaceTeamService(teams, deleteRole, auditEvents);
  }

  @Test
  void deletesTheTeam() {
    service.handle(new DeleteWorkspaceTeamCommand(team.workspaceId(), team.id(), ACTOR));

    verify(teams).deleteById(team.id());
  }

  @Test
  void recordsAnAuditEvent() {
    service.handle(new DeleteWorkspaceTeamCommand(team.workspaceId(), team.id(), ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("workspace_team.deleted"),
            eq("WorkspaceTeam"),
            eq(team.id().toString()),
            any());
  }

  @Test
  void rejectsAnUnknownTeamId() {
    UUID unknownTeamId = UUID.randomUUID();
    when(teams.findById(unknownTeamId)).thenReturn(Optional.empty());
    DeleteWorkspaceTeamCommand command =
        new DeleteWorkspaceTeamCommand(team.workspaceId(), unknownTeamId, ACTOR);

    assertThatExceptionOfType(WorkspaceTeamNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).deleteById(any());
  }

  @Test
  void rejectsATeamBelongingToADifferentWorkspace() {
    DeleteWorkspaceTeamCommand command =
        new DeleteWorkspaceTeamCommand(UUID.randomUUID(), team.id(), ACTOR);

    assertThatExceptionOfType(WorkspaceTeamNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).deleteById(any());
  }

  // Live UX request, 2026-09-28: a role this team's own deletion orphans (not grouped or assigned
  // anywhere else) is now deleted too, not just left ungrouped.
  @Test
  void deletesARoleThatIsNowOrphanedByTheTeamsOwnDeletion() {
    UUID roleId = UUID.randomUUID();
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(roleId));
    when(teams.isRoleGroupedInAnyOtherTeam(roleId, team.id())).thenReturn(false);

    service.handle(new DeleteWorkspaceTeamCommand(team.workspaceId(), team.id(), ACTOR));

    ArgumentCaptor<DeleteWorkspaceRoleCommand> captured =
        ArgumentCaptor.forClass(DeleteWorkspaceRoleCommand.class);
    verify(deleteRole).handle(captured.capture());
    assertThatIsForRoleAndWorkspace(captured.getValue(), roleId, team.workspaceId());
  }

  @Test
  void leavesARoleGroupedInAnotherTeamAlone() {
    UUID roleId = UUID.randomUUID();
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(roleId));
    when(teams.isRoleGroupedInAnyOtherTeam(roleId, team.id())).thenReturn(true);

    service.handle(new DeleteWorkspaceTeamCommand(team.workspaceId(), team.id(), ACTOR));

    verify(deleteRole, never()).handle(any());
  }

  // None of DeleteWorkspaceRoleUseCase's own guard exceptions should abort the team deletion
  // itself — the team is already deleted by the time this attempt runs, and a role surviving
  // (ungrouped) is never treated as a failure of this operation.
  @Test
  void stillDeletesTheTeamWhenAnOrphanedRoleCannotBeSafelyDeleted() {
    UUID roleId = UUID.randomUUID();
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(roleId));
    when(teams.isRoleGroupedInAnyOtherTeam(roleId, team.id())).thenReturn(false);
    doThrow(new WorkspaceRoleStillAssignedException(roleId)).when(deleteRole).handle(any());

    service.handle(new DeleteWorkspaceTeamCommand(team.workspaceId(), team.id(), ACTOR));

    verify(teams).deleteById(team.id());
  }

  @Test
  void alsoToleratesEveryOtherDeleteRoleGuardException() {
    UUID roleId = UUID.randomUUID();
    when(teams.findRoleIdsByTeamId(team.id())).thenReturn(List.of(roleId));
    when(teams.isRoleGroupedInAnyOtherTeam(roleId, team.id())).thenReturn(false);

    for (RuntimeException guardException :
        List.of(
            new WorkspaceRoleHasChildRolesException(roleId),
            new CannotDeleteReservedWorkspaceRoleException(roleId),
            new CannotDemoteLastAdminException(team.workspaceId()))) {
      doThrow(guardException).when(deleteRole).handle(any());

      service.handle(new DeleteWorkspaceTeamCommand(team.workspaceId(), team.id(), ACTOR));
    }

    verify(teams, times(3)).deleteById(team.id());
  }

  private static void assertThatIsForRoleAndWorkspace(
      final DeleteWorkspaceRoleCommand command, final UUID roleId, final UUID workspaceId) {
    assertThat(command.roleId()).isEqualTo(roleId);
    assertThat(command.workspaceId()).isEqualTo(workspaceId);
  }
}
