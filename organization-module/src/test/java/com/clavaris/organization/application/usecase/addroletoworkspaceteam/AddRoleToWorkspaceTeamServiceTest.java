package com.clavaris.organization.application.usecase.addroletoworkspaceteam;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamNotFoundException;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.model.WorkspaceTeam;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AddRoleToWorkspaceTeamServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceTeamRepository teams;
  private WorkspaceRepository workspaces;
  private WorkspaceRoleRepository roles;
  private AuditEventRecorder auditEvents;
  private AddRoleToWorkspaceTeamService service;

  private Organization organization;
  private Workspace workspace;
  private WorkspaceTeam team;
  private WorkspaceRole role;

  @BeforeEach
  void setUp() {
    teams = mock(WorkspaceTeamRepository.class);
    workspaces = mock(WorkspaceRepository.class);
    roles = mock(WorkspaceRoleRepository.class);
    auditEvents = mock(AuditEventRecorder.class);

    organization = Organization.register("Acme Co", UUID.randomUUID());
    workspace = Workspace.register(organization.id(), "Engineering");
    team = WorkspaceTeam.define(workspace.id(), "QA");
    role = WorkspaceRole.define(organization.id(), "Tester", null, Set.of());

    when(teams.findById(team.id())).thenReturn(Optional.of(team));
    when(workspaces.findById(workspace.id())).thenReturn(Optional.of(workspace));
    when(roles.findById(role.id())).thenReturn(Optional.of(role));
    when(teams.findTeamIdForRoleInWorkspace(workspace.id(), role.id()))
        .thenReturn(Optional.empty());

    service = new AddRoleToWorkspaceTeamService(teams, workspaces, roles, auditEvents);
  }

  @Test
  void addsTheRoleToTheTeam() {
    service.handle(new AddRoleToWorkspaceTeamCommand(workspace.id(), team.id(), role.id(), ACTOR));

    verify(teams).addRoleToTeam(team.id(), role.id());
  }

  @Test
  void recordsAnAuditEvent() {
    service.handle(new AddRoleToWorkspaceTeamCommand(workspace.id(), team.id(), role.id(), ACTOR));

    verify(auditEvents)
        .write(
            org.mockito.ArgumentMatchers.eq(ACTOR),
            org.mockito.ArgumentMatchers.eq("workspace_team.role_added"),
            org.mockito.ArgumentMatchers.eq("WorkspaceTeam"),
            org.mockito.ArgumentMatchers.eq(team.id().toString()),
            any());
  }

  @Test
  void isIdempotentWhenTheRoleIsAlreadyInThisSameTeam() {
    when(teams.findTeamIdForRoleInWorkspace(workspace.id(), role.id()))
        .thenReturn(Optional.of(team.id()));

    service.handle(new AddRoleToWorkspaceTeamCommand(workspace.id(), team.id(), role.id(), ACTOR));

    verify(teams, never()).addRoleToTeam(any(), any());
  }

  @Test
  void rejectsARoleAlreadyInADifferentTeamInTheSameWorkspace() {
    UUID otherTeamId = UUID.randomUUID();
    when(teams.findTeamIdForRoleInWorkspace(workspace.id(), role.id()))
        .thenReturn(Optional.of(otherTeamId));
    AddRoleToWorkspaceTeamCommand command =
        new AddRoleToWorkspaceTeamCommand(workspace.id(), team.id(), role.id(), ACTOR);

    assertThatExceptionOfType(WorkspaceRoleAlreadyInAnotherTeamException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).addRoleToTeam(any(), any());
  }

  @Test
  void rejectsAnUnknownTeamId() {
    UUID unknownTeamId = UUID.randomUUID();
    when(teams.findById(unknownTeamId)).thenReturn(Optional.empty());
    AddRoleToWorkspaceTeamCommand command =
        new AddRoleToWorkspaceTeamCommand(workspace.id(), unknownTeamId, role.id(), ACTOR);

    assertThatExceptionOfType(WorkspaceTeamNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).addRoleToTeam(any(), any());
  }

  @Test
  void rejectsATeamBelongingToADifferentWorkspace() {
    AddRoleToWorkspaceTeamCommand command =
        new AddRoleToWorkspaceTeamCommand(UUID.randomUUID(), team.id(), role.id(), ACTOR);

    assertThatExceptionOfType(WorkspaceTeamNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).addRoleToTeam(any(), any());
  }

  @Test
  void rejectsAnUnknownRoleId() {
    UUID unknownRoleId = UUID.randomUUID();
    when(roles.findById(unknownRoleId)).thenReturn(Optional.empty());
    AddRoleToWorkspaceTeamCommand command =
        new AddRoleToWorkspaceTeamCommand(workspace.id(), team.id(), unknownRoleId, ACTOR);

    assertThatExceptionOfType(WorkspaceRoleNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).addRoleToTeam(any(), any());
  }

  @Test
  void rejectsARoleBelongingToADifferentOrganization() {
    WorkspaceRole otherOrgRole = WorkspaceRole.define(UUID.randomUUID(), "Foreign", null, Set.of());
    when(roles.findById(otherOrgRole.id())).thenReturn(Optional.of(otherOrgRole));
    AddRoleToWorkspaceTeamCommand command =
        new AddRoleToWorkspaceTeamCommand(workspace.id(), team.id(), otherOrgRole.id(), ACTOR);

    assertThatExceptionOfType(WorkspaceRoleNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).addRoleToTeam(any(), any());
  }

  @Test
  void rejectsWhenTheTeamsOwnWorkspaceNoLongerExists() {
    when(workspaces.findById(workspace.id())).thenReturn(Optional.empty());
    AddRoleToWorkspaceTeamCommand command =
        new AddRoleToWorkspaceTeamCommand(workspace.id(), team.id(), role.id(), ACTOR);

    assertThatExceptionOfType(WorkspaceNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(teams, never()).addRoleToTeam(any(), any());
  }
}
