package com.clavaris.organization.application.usecase.removerolefromworkspaceteam;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoveRoleFromWorkspaceTeamServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceTeamRepository teams;
  private AuditEventRecorder auditEvents;
  private RemoveRoleFromWorkspaceTeamService service;

  private WorkspaceTeam team;
  private UUID roleId;

  @BeforeEach
  void setUp() {
    teams = mock(WorkspaceTeamRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    team = WorkspaceTeam.define(UUID.randomUUID(), "QA");
    roleId = UUID.randomUUID();
    when(teams.findById(team.id())).thenReturn(Optional.of(team));
    service = new RemoveRoleFromWorkspaceTeamService(teams, auditEvents);
  }

  @Test
  void removesTheRoleFromTheTeam() {
    service.handle(
        new RemoveRoleFromWorkspaceTeamCommand(team.workspaceId(), team.id(), roleId, ACTOR));

    verify(teams).removeRoleFromTeam(team.id(), roleId);
  }

  @Test
  void recordsAnAuditEvent() {
    service.handle(
        new RemoveRoleFromWorkspaceTeamCommand(team.workspaceId(), team.id(), roleId, ACTOR));

    verify(auditEvents)
        .write(
            org.mockito.ArgumentMatchers.eq(ACTOR),
            org.mockito.ArgumentMatchers.eq("workspace_team.role_removed"),
            org.mockito.ArgumentMatchers.eq("WorkspaceTeam"),
            org.mockito.ArgumentMatchers.eq(team.id().toString()),
            any());
  }

  @Test
  void rejectsAnUnknownTeamId() {
    UUID unknownTeamId = UUID.randomUUID();
    when(teams.findById(unknownTeamId)).thenReturn(Optional.empty());
    RemoveRoleFromWorkspaceTeamCommand command =
        new RemoveRoleFromWorkspaceTeamCommand(team.workspaceId(), unknownTeamId, roleId, ACTOR);

    assertThatExceptionOfType(WorkspaceTeamNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).removeRoleFromTeam(any(), any());
  }

  @Test
  void rejectsATeamBelongingToADifferentWorkspace() {
    RemoveRoleFromWorkspaceTeamCommand command =
        new RemoveRoleFromWorkspaceTeamCommand(UUID.randomUUID(), team.id(), roleId, ACTOR);

    assertThatExceptionOfType(WorkspaceTeamNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).removeRoleFromTeam(any(), any());
  }
}
