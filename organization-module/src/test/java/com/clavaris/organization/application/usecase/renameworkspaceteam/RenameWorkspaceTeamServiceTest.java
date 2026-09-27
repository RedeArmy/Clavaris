package com.clavaris.organization.application.usecase.renameworkspaceteam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createworkspaceteam.DuplicateWorkspaceTeamNameException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RenameWorkspaceTeamServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceTeamRepository teams;
  private AuditEventRecorder auditEvents;
  private RenameWorkspaceTeamService service;

  private UUID workspaceId;
  private WorkspaceTeam team;

  @BeforeEach
  void setUp() {
    teams = mock(WorkspaceTeamRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    workspaceId = UUID.randomUUID();
    team = WorkspaceTeam.define(workspaceId, "QA");
    when(teams.findById(team.id())).thenReturn(Optional.of(team));
    when(teams.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(team));
    service = new RenameWorkspaceTeamService(teams, auditEvents);
  }

  @Test
  void renamesTheTeam() {
    WorkspaceTeam renamed =
        service.handle(
            new RenameWorkspaceTeamCommand(workspaceId, team.id(), "Quality Assurance", ACTOR));

    assertThat(renamed.name()).isEqualTo("Quality Assurance");
    verify(teams).save(renamed);
  }

  @Test
  void recordsAnAuditEvent() {
    service.handle(
        new RenameWorkspaceTeamCommand(workspaceId, team.id(), "Quality Assurance", ACTOR));

    verify(auditEvents)
        .write(
            org.mockito.ArgumentMatchers.eq(ACTOR),
            org.mockito.ArgumentMatchers.eq("workspace_team.renamed"),
            org.mockito.ArgumentMatchers.eq("WorkspaceTeam"),
            org.mockito.ArgumentMatchers.eq(team.id().toString()),
            any());
  }

  @Test
  void allowsKeepingItsOwnCurrentNameUnchanged() {
    WorkspaceTeam renamed =
        service.handle(new RenameWorkspaceTeamCommand(workspaceId, team.id(), "QA", ACTOR));

    assertThat(renamed.name()).isEqualTo("QA");
  }

  @Test
  void rejectsAnUnknownTeamId() {
    UUID unknownTeamId = UUID.randomUUID();
    when(teams.findById(unknownTeamId)).thenReturn(Optional.empty());
    RenameWorkspaceTeamCommand command =
        new RenameWorkspaceTeamCommand(workspaceId, unknownTeamId, "Anything", ACTOR);

    assertThatExceptionOfType(WorkspaceTeamNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).save(any());
  }

  @Test
  void rejectsATeamBelongingToADifferentWorkspace() {
    RenameWorkspaceTeamCommand command =
        new RenameWorkspaceTeamCommand(UUID.randomUUID(), team.id(), "Anything", ACTOR);

    assertThatExceptionOfType(WorkspaceTeamNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).save(any());
  }

  @Test
  void rejectsRenamingToAnAlreadyUsedNameWithinTheSameWorkspace() {
    WorkspaceTeam other = WorkspaceTeam.define(workspaceId, "Support");
    when(teams.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(team, other));
    RenameWorkspaceTeamCommand command =
        new RenameWorkspaceTeamCommand(workspaceId, team.id(), "Support", ACTOR);

    assertThatExceptionOfType(DuplicateWorkspaceTeamNameException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).save(any());
  }
}
