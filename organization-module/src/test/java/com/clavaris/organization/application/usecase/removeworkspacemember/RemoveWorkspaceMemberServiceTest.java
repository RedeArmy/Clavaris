package com.clavaris.organization.application.usecase.removeworkspacemember;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoveWorkspaceMemberServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceMembershipRepository memberships;
  private WorkspaceRepository workspaces;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private RemoveWorkspaceMemberService service;

  @BeforeEach
  void setUp() {
    memberships = mock(WorkspaceMembershipRepository.class);
    workspaces = mock(WorkspaceRepository.class);
    when(workspaces.findOrganizationIdById(any())).thenReturn(Optional.of(UUID.randomUUID()));
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service = new RemoveWorkspaceMemberService(memberships, workspaces, auditEvents, outbox);
  }

  private WorkspaceMembership existingMembership(
      final UUID workspaceId, final UUID accountId, final WorkspaceRole role) {
    WorkspaceMembership membership = WorkspaceMembership.join(workspaceId, accountId, role);
    when(memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId))
        .thenReturn(Optional.of(membership));
    return membership;
  }

  @Test
  void removesAMemberWithoutCheckingTheAdminCount() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership membership =
        existingMembership(workspaceId, accountId, WorkspaceRole.MEMBER);

    service.handle(new RemoveWorkspaceMemberCommand(workspaceId, accountId, ACTOR));

    verify(memberships, never()).countByWorkspaceIdAndRole(any(), any());
    verify(memberships).deleteById(membership.id());
  }

  @Test
  void removesAnAdminWhenAnotherAdminRemains() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership membership =
        existingMembership(workspaceId, accountId, WorkspaceRole.ADMIN);
    when(memberships.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN)).thenReturn(2L);

    service.handle(new RemoveWorkspaceMemberCommand(workspaceId, accountId, ACTOR));

    verify(memberships).deleteById(membership.id());
  }

  @Test
  void rejectsRemovingTheLastAdminWithoutDeletingAnything() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, WorkspaceRole.ADMIN);
    when(memberships.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN)).thenReturn(1L);
    RemoveWorkspaceMemberCommand command =
        new RemoveWorkspaceMemberCommand(workspaceId, accountId, ACTOR);

    assertThatExceptionOfType(CannotRemoveLastAdminException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).deleteById(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  @Test
  void recordsAnAuditEventAndAnOutboxEventOnSuccess() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership membership =
        existingMembership(workspaceId, accountId, WorkspaceRole.MEMBER);

    service.handle(new RemoveWorkspaceMemberCommand(workspaceId, accountId, ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("workspace_membership.removed"),
            eq("WorkspaceMembership"),
            eq(membership.id().toString()),
            any());
    verify(outbox)
        .write(
            eq("WorkspaceMembership"),
            eq("workspace_membership.removed"),
            eq(membership.id()),
            any(),
            any());
  }

  @Test
  void rejectsAnUnknownMembership() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId))
        .thenReturn(Optional.empty());
    RemoveWorkspaceMemberCommand command =
        new RemoveWorkspaceMemberCommand(workspaceId, accountId, ACTOR);

    assertThatExceptionOfType(WorkspaceMembershipNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).deleteById(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  @Test
  void refusesToProceedWhenTheMembershipsOwnWorkspaceNoLongerExists() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, WorkspaceRole.MEMBER);
    when(workspaces.findOrganizationIdById(workspaceId)).thenReturn(Optional.empty());
    RemoveWorkspaceMemberCommand command =
        new RemoveWorkspaceMemberCommand(workspaceId, accountId, ACTOR);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).deleteById(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }
}
