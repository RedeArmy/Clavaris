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
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
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

class ChangeWorkspaceMemberRoleServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceMembershipRepository memberships;
  private WorkspaceRepository workspaces;
  private WorkspaceRoleRepository roles;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private ChangeWorkspaceMemberRoleService service;

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
    when(roles.findById(manageMembersRole.id())).thenReturn(Optional.of(manageMembersRole));
    when(roles.findById(plainRole.id())).thenReturn(Optional.of(plainRole));

    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service =
        new ChangeWorkspaceMemberRoleService(memberships, workspaces, roles, auditEvents, outbox);
  }

  private WorkspaceMembership existingMembership(
      final UUID workspaceId, final UUID accountId, final UUID roleId) {
    WorkspaceMembership membership = WorkspaceMembership.join(workspaceId, accountId, roleId);
    when(memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId))
        .thenReturn(Optional.of(membership));
    return membership;
  }

  @Test
  void promotesAMemberToTheManageMembersRoleWithoutCheckingTheHolderCount() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, plainRole.id());

    WorkspaceMembership updated =
        service.handle(
            new ChangeWorkspaceMemberRoleCommand(
                workspaceId, accountId, manageMembersRole.id(), ACTOR));

    assertThat(updated.roleId()).isEqualTo(manageMembersRole.id());
    verify(memberships).save(updated);
  }

  @Test
  void demotesAHolderWhenAnotherHolderRemains() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, manageMembersRole.id());
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(
            List.of(
                WorkspaceMembership.join(workspaceId, accountId, manageMembersRole.id()),
                WorkspaceMembership.join(workspaceId, UUID.randomUUID(), manageMembersRole.id())));

    WorkspaceMembership updated =
        service.handle(
            new ChangeWorkspaceMemberRoleCommand(workspaceId, accountId, plainRole.id(), ACTOR));

    assertThat(updated.roleId()).isEqualTo(plainRole.id());
    verify(memberships).save(updated);
  }

  @Test
  void rejectsDemotingTheLastHolderWithoutSavingAnything() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, manageMembersRole.id());
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(
            List.of(WorkspaceMembership.join(workspaceId, accountId, manageMembersRole.id())));
    ChangeWorkspaceMemberRoleCommand command =
        new ChangeWorkspaceMemberRoleCommand(workspaceId, accountId, plainRole.id(), ACTOR);

    assertThatExceptionOfType(CannotDemoteLastAdminException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  @Test
  void rejectsUnassigningTheLastHolderWithoutSavingAnything() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, manageMembersRole.id());
    when(memberships.findAllByWorkspaceId(workspaceId))
        .thenReturn(
            List.of(WorkspaceMembership.join(workspaceId, accountId, manageMembersRole.id())));
    ChangeWorkspaceMemberRoleCommand command =
        new ChangeWorkspaceMemberRoleCommand(workspaceId, accountId, null, ACTOR);

    assertThatExceptionOfType(CannotDemoteLastAdminException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
  }

  @Test
  void recordsAnAuditEventAndAnOutboxEventOnSuccess() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, plainRole.id());

    WorkspaceMembership updated =
        service.handle(
            new ChangeWorkspaceMemberRoleCommand(
                workspaceId, accountId, manageMembersRole.id(), ACTOR));

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
            any(),
            any());
  }

  @Test
  void rejectsAnUnknownMembership() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId))
        .thenReturn(Optional.empty());
    ChangeWorkspaceMemberRoleCommand command =
        new ChangeWorkspaceMemberRoleCommand(workspaceId, accountId, manageMembersRole.id(), ACTOR);

    assertThatExceptionOfType(WorkspaceMembershipNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  // ADR-0027: newRoleId must belong to this same membership's own Organization.
  @Test
  void rejectsAnUnknownNewRoleIdWithoutSavingAnything() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, plainRole.id());
    UUID unknownRoleId = UUID.randomUUID();
    when(roles.findById(unknownRoleId)).thenReturn(Optional.empty());
    ChangeWorkspaceMemberRoleCommand command =
        new ChangeWorkspaceMemberRoleCommand(workspaceId, accountId, unknownRoleId, ACTOR);

    assertThatExceptionOfType(WorkspaceRoleNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
  }

  @Test
  void allowsUnassigningTheRoleEntirely() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, plainRole.id());

    WorkspaceMembership updated =
        service.handle(new ChangeWorkspaceMemberRoleCommand(workspaceId, accountId, null, ACTOR));

    assertThat(updated.roleId()).isNull();
    verify(memberships).save(updated);
  }

  // Unlike RemoveWorkspaceMemberService, this lookup now runs (and can fail-fast) before any
  // save — ManageMembersGuard itself needs organizationId to load this Organization's own roles,
  // so the lookup moved ahead of the mutating save, not after it.
  @Test
  void refusesToProceedWhenTheMembershipsOwnWorkspaceNoLongerExists() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    existingMembership(workspaceId, accountId, plainRole.id());
    when(workspaces.findOrganizationIdById(workspaceId)).thenReturn(Optional.empty());
    ChangeWorkspaceMemberRoleCommand command =
        new ChangeWorkspaceMemberRoleCommand(workspaceId, accountId, manageMembersRole.id(), ACTOR);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }
}
