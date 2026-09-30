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
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoveWorkspaceMemberServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceMembershipRepository memberships;
  private WorkspaceRepository workspaces;
  private WorkspaceRoleRepository roles;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private WorkspaceMemberRefreshTokenRevoker refreshTokenRevoker;
  private WorkspaceMemberAccountRevoker accountRevoker;
  private RemoveWorkspaceMemberService service;

  private UUID organizationId;
  private WorkspaceRole manageMembersRole;
  private WorkspaceRole plainRole;

  @BeforeEach
  void setUp() {
    memberships = mock(WorkspaceMembershipRepository.class);
    workspaces = mock(WorkspaceRepository.class);
    roles = mock(WorkspaceRoleRepository.class);
    organizationId = UUID.randomUUID();
    when(workspaces.findOrganizationIdById(any())).thenReturn(Optional.of(organizationId));

    manageMembersRole = WorkspaceRole.defineReserved(organizationId, "Admin");
    plainRole = WorkspaceRole.define(organizationId, "Member", null, Set.of());
    when(roles.findAllByOrganizationId(organizationId))
        .thenReturn(List.of(manageMembersRole, plainRole));

    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    refreshTokenRevoker = mock(WorkspaceMemberRefreshTokenRevoker.class);
    accountRevoker = mock(WorkspaceMemberAccountRevoker.class);
    service =
        new RemoveWorkspaceMemberService(
            memberships,
            workspaces,
            roles,
            auditEvents,
            outbox,
            refreshTokenRevoker,
            accountRevoker);
  }

  private WorkspaceMembership existingMembership(
      final UUID workspaceId, final UUID accountId, final UUID roleId) {
    WorkspaceMembership membership = WorkspaceMembership.join(workspaceId, accountId, roleId);
    when(memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId))
        .thenReturn(Optional.of(membership));
    return membership;
  }

  @Test
  void removesAMemberWithoutCheckingTheHolderCount() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership membership = existingMembership(workspaceId, accountId, plainRole.id());

    service.handle(new RemoveWorkspaceMemberCommand(workspaceId, accountId, ACTOR));

    verify(memberships).deleteById(membership.id());
  }

  @Test
  void removesAHolderWhenAnotherHolderRemains() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership membership =
        existingMembership(workspaceId, accountId, manageMembersRole.id());
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(
            List.of(
                membership,
                WorkspaceMembership.join(workspaceId, UUID.randomUUID(), manageMembersRole.id())));

    service.handle(new RemoveWorkspaceMemberCommand(workspaceId, accountId, ACTOR));

    verify(memberships).deleteById(membership.id());
  }

  @Test
  void rejectsRemovingTheLastHolderWithoutDeletingAnything() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership membership =
        existingMembership(workspaceId, accountId, manageMembersRole.id());
    when(memberships.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(membership));
    RemoveWorkspaceMemberCommand command =
        new RemoveWorkspaceMemberCommand(workspaceId, accountId, ACTOR);

    assertThatExceptionOfType(CannotRemoveLastAdminException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).deleteById(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
    verifyNoInteractions(refreshTokenRevoker);
    verifyNoInteractions(accountRevoker);
  }

  // TD-WS-002 (closed, 2026-09-06 mitigation): see WorkspaceMemberRefreshTokenRevoker's own
  // Javadoc.
  @Test
  void revokesEveryRefreshTokenForTheRemovedMembersAccountOnSuccess() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, plainRole.id());

    service.handle(new RemoveWorkspaceMemberCommand(workspaceId, accountId, ACTOR));

    verify(refreshTokenRevoker).revokeAllRefreshTokensFor(accountId);
  }

  // TD-WS-002 (closed): the rest of the cascade — see WorkspaceMemberAccountRevoker's own Javadoc
  // for why this is safe to run unconditionally under today's real Account-Workspace 1:1 invariant.
  @Test
  void revokesTheRemovedMembersAccountAccessOnSuccess() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, plainRole.id());

    service.handle(new RemoveWorkspaceMemberCommand(workspaceId, accountId, ACTOR));

    verify(accountRevoker).revokeAllAccessFor(accountId);
  }

  @Test
  void recordsAnAuditEventAndAnOutboxEventOnSuccess() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership membership = existingMembership(workspaceId, accountId, plainRole.id());

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
    verifyNoInteractions(refreshTokenRevoker);
    verifyNoInteractions(accountRevoker);
  }

  @Test
  void refusesToProceedWhenTheMembershipsOwnWorkspaceNoLongerExists() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, plainRole.id());
    when(workspaces.findOrganizationIdById(workspaceId)).thenReturn(Optional.empty());
    RemoveWorkspaceMemberCommand command =
        new RemoveWorkspaceMemberCommand(workspaceId, accountId, ACTOR);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).deleteById(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
    verifyNoInteractions(refreshTokenRevoker);
    verifyNoInteractions(accountRevoker);
  }
}
