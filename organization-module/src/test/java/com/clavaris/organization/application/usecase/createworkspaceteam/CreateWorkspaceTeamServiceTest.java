package com.clavaris.organization.application.usecase.createworkspaceteam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateWorkspaceTeamServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceTeamRepository teams;
  private WorkspaceRepository workspaces;
  private AuditEventRecorder auditEvents;
  private CreateWorkspaceTeamService service;

  private UUID workspaceId;

  @BeforeEach
  void setUp() {
    teams = mock(WorkspaceTeamRepository.class);
    workspaces = mock(WorkspaceRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    workspaceId = UUID.randomUUID();
    when(workspaces.findById(workspaceId))
        .thenReturn(Optional.of(Workspace.register(UUID.randomUUID(), "Engineering")));
    when(teams.findAllByWorkspaceId(workspaceId)).thenReturn(List.of());
    service = new CreateWorkspaceTeamService(teams, workspaces, auditEvents);
  }

  @Test
  void createsAndPersistsTheTeam() {
    WorkspaceTeam team = service.handle(new CreateWorkspaceTeamCommand(workspaceId, "QA", ACTOR));

    assertThat(team.name()).isEqualTo("QA");
    assertThat(team.workspaceId()).isEqualTo(workspaceId);
    verify(teams).saveAndFlush(team);
  }

  @Test
  void recordsAnAuditEvent() {
    WorkspaceTeam team = service.handle(new CreateWorkspaceTeamCommand(workspaceId, "QA", ACTOR));

    verify(auditEvents)
        .write(
            org.mockito.ArgumentMatchers.eq(ACTOR),
            org.mockito.ArgumentMatchers.eq("workspace_team.created"),
            org.mockito.ArgumentMatchers.eq("WorkspaceTeam"),
            org.mockito.ArgumentMatchers.eq(team.id().toString()),
            any());
  }

  @Test
  void rejectsAnUnknownWorkspaceWithoutPersistingAnything() {
    UUID unknownWorkspaceId = UUID.randomUUID();
    when(workspaces.findById(unknownWorkspaceId)).thenReturn(Optional.empty());
    CreateWorkspaceTeamCommand command =
        new CreateWorkspaceTeamCommand(unknownWorkspaceId, "QA", ACTOR);

    assertThatExceptionOfType(WorkspaceNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).save(any());
  }

  @Test
  void rejectsADuplicateNameWithinTheSameWorkspace() {
    WorkspaceTeam existing = WorkspaceTeam.define(workspaceId, "QA");
    when(teams.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(existing));
    CreateWorkspaceTeamCommand command = new CreateWorkspaceTeamCommand(workspaceId, "QA", ACTOR);

    assertThatExceptionOfType(DuplicateWorkspaceTeamNameException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).save(any());
  }

  @Test
  void translatesADataIntegrityViolationFromALostRaceIntoTheTypedException() {
    // TD-SEC-060: the pre-check above passes (no existing row yet, per setUp's own stub), but
    // ux_workspace_teams_workspace_id_name still fires at saveAndFlush time — simulating a
    // concurrent request that created the same name first.
    doThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"))
        .when(teams)
        .saveAndFlush(any());
    CreateWorkspaceTeamCommand command = new CreateWorkspaceTeamCommand(workspaceId, "QA", ACTOR);

    assertThatExceptionOfType(DuplicateWorkspaceTeamNameException.class)
        .isThrownBy(() -> service.handle(command));

    org.mockito.Mockito.verifyNoInteractions(auditEvents);
  }
}
