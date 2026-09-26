package com.clavaris.organization.application.usecase.deleteworkspacerole;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeleteWorkspaceRoleServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceRoleRepository roles;
  private WorkspaceMembershipRepository memberships;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private DeleteWorkspaceRoleService service;

  private UUID organizationId;
  private WorkspaceRole role;

  @BeforeEach
  void setUp() {
    roles = mock(WorkspaceRoleRepository.class);
    memberships = mock(WorkspaceMembershipRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    organizationId = UUID.randomUUID();
    role = WorkspaceRole.define(organizationId, "Supervisor", null, Set.of());
    when(roles.findById(role.id())).thenReturn(Optional.of(role));
    when(memberships.existsByRoleId(role.id())).thenReturn(false);
    service = new DeleteWorkspaceRoleService(roles, memberships, auditEvents, outbox);
  }

  @Test
  void deletesAnUnassignedNonReservedRole() {
    service.handle(new DeleteWorkspaceRoleCommand(role.id(), ACTOR));

    verify(roles).deleteById(role.id());
  }

  @Test
  void recordsAnAuditEventAndAnOutboxEvent() {
    service.handle(new DeleteWorkspaceRoleCommand(role.id(), ACTOR));

    verify(auditEvents)
        .write(
            org.mockito.ArgumentMatchers.eq(ACTOR),
            org.mockito.ArgumentMatchers.eq("workspace_role.deleted"),
            org.mockito.ArgumentMatchers.eq("WorkspaceRole"),
            org.mockito.ArgumentMatchers.eq(role.id().toString()),
            any());
    verify(outbox)
        .write(
            org.mockito.ArgumentMatchers.eq("WorkspaceRole"),
            org.mockito.ArgumentMatchers.eq("workspace_role.deleted"),
            org.mockito.ArgumentMatchers.eq(role.id()),
            org.mockito.ArgumentMatchers.eq(organizationId),
            any());
  }

  @Test
  void rejectsAnUnknownRoleId() {
    UUID unknownRoleId = UUID.randomUUID();
    when(roles.findById(unknownRoleId)).thenReturn(Optional.empty());
    DeleteWorkspaceRoleCommand command = new DeleteWorkspaceRoleCommand(unknownRoleId, ACTOR);

    assertThatExceptionOfType(WorkspaceRoleNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).deleteById(any());
  }

  @Test
  void rejectsDeletingTheReservedRole() {
    WorkspaceRole reserved = WorkspaceRole.defineReserved(organizationId, "Admin");
    when(roles.findById(reserved.id())).thenReturn(Optional.of(reserved));
    DeleteWorkspaceRoleCommand command = new DeleteWorkspaceRoleCommand(reserved.id(), ACTOR);

    assertThatExceptionOfType(CannotDeleteReservedWorkspaceRoleException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).deleteById(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  @Test
  void rejectsDeletingARoleStillAssignedToAMember() {
    when(memberships.existsByRoleId(role.id())).thenReturn(true);
    DeleteWorkspaceRoleCommand command = new DeleteWorkspaceRoleCommand(role.id(), ACTOR);

    assertThatExceptionOfType(WorkspaceRoleStillAssignedException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).deleteById(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }
}
