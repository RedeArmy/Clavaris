package com.clavaris.organization.application.usecase.changeworkspacememberrole;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChangeWorkspaceMemberRoleServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceMembershipRepository memberships;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private ChangeWorkspaceMemberRoleService service;

  @BeforeEach
  void setUp() {
    memberships = mock(WorkspaceMembershipRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service = new ChangeWorkspaceMemberRoleService(memberships, auditEvents, outbox);
  }

  private WorkspaceMembership existingMembership(
      final UUID workspaceId, final UUID accountId, final WorkspaceRole role) {
    WorkspaceMembership membership = WorkspaceMembership.join(workspaceId, accountId, role);
    when(memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId))
        .thenReturn(Optional.of(membership));
    return membership;
  }

  @Test
  void promotesAMemberToAdminWithoutCheckingTheAdminCount() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, WorkspaceRole.MEMBER);

    WorkspaceMembership updated =
        service.handle(
            new ChangeWorkspaceMemberRoleCommand(
                workspaceId, accountId, WorkspaceRole.ADMIN, ACTOR));

    assertThat(updated.role()).isEqualTo(WorkspaceRole.ADMIN);
    verify(memberships, never()).countByWorkspaceIdAndRole(any(), any());
    verify(memberships).save(updated);
  }

  @Test
  void demotesAnAdminWhenAnotherAdminRemains() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, WorkspaceRole.ADMIN);
    when(memberships.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN)).thenReturn(2L);

    WorkspaceMembership updated =
        service.handle(
            new ChangeWorkspaceMemberRoleCommand(
                workspaceId, accountId, WorkspaceRole.MEMBER, ACTOR));

    assertThat(updated.role()).isEqualTo(WorkspaceRole.MEMBER);
    verify(memberships).save(updated);
  }

  @Test
  void rejectsDemotingTheLastAdminWithoutSavingAnything() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, WorkspaceRole.ADMIN);
    when(memberships.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN)).thenReturn(1L);
    ChangeWorkspaceMemberRoleCommand command =
        new ChangeWorkspaceMemberRoleCommand(workspaceId, accountId, WorkspaceRole.MEMBER, ACTOR);

    assertThatExceptionOfType(CannotDemoteLastAdminException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  @Test
  void recordsAnAuditEventAndAnOutboxEventOnSuccess() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, WorkspaceRole.MEMBER);

    WorkspaceMembership updated =
        service.handle(
            new ChangeWorkspaceMemberRoleCommand(
                workspaceId, accountId, WorkspaceRole.ADMIN, ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("workspace_membership.role_changed"),
            eq("WorkspaceMembership"),
            eq(updated.id().toString()),
            any());
    verify(outbox)
        .write(
            eq("WorkspaceMembership"),
            eq("workspace_membership.role_changed"),
            eq(updated.id()),
            any());
  }

  @Test
  void rejectsAnUnknownMembership() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId))
        .thenReturn(Optional.empty());
    ChangeWorkspaceMemberRoleCommand command =
        new ChangeWorkspaceMemberRoleCommand(workspaceId, accountId, WorkspaceRole.ADMIN, ACTOR);

    assertThatExceptionOfType(WorkspaceMembershipNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }
}
