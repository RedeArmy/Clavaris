package com.clavaris.organization.application.usecase.deleteworkspacerole;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.model.ReservedWorkspacePermissions;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role));
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

  // ADR-0028 §3: the reserved role is no longer permanently undeletable — allowed once some other
  // role's own effective permissions already cover both reserved permissions.
  @Test
  void allowsDeletingTheReservedRoleOnceASubstituteRoleExists() {
    WorkspaceRole reserved = WorkspaceRole.defineReserved(organizationId, "Admin");
    WorkspaceRole substitute =
        WorkspaceRole.define(organizationId, "Owner", null, ReservedWorkspacePermissions.ALL);
    when(roles.findById(reserved.id())).thenReturn(Optional.of(reserved));
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(reserved, substitute));
    when(memberships.existsByRoleId(reserved.id())).thenReturn(false);
    DeleteWorkspaceRoleCommand command = new DeleteWorkspaceRoleCommand(reserved.id(), ACTOR);

    service.handle(command);

    verify(roles).deleteById(reserved.id());
  }

  // A substitute must cover BOTH reserved permissions — one alone still leaves the Organization
  // without a real path to manage the other, so this must not be treated as sufficient.
  @Test
  void rejectsDeletingTheReservedRoleWhenTheOnlySubstituteIsMissingOneReservedPermission() {
    WorkspaceRole reserved = WorkspaceRole.defineReserved(organizationId, "Admin");
    WorkspaceRole partialSubstitute =
        WorkspaceRole.define(
            organizationId, "Partial", null, Set.of(ReservedWorkspacePermissions.MANAGE_MEMBERS));
    when(roles.findById(reserved.id())).thenReturn(Optional.of(reserved));
    when(roles.findAllByOrganizationId(organizationId))
        .thenReturn(List.of(reserved, partialSubstitute));
    DeleteWorkspaceRoleCommand command = new DeleteWorkspaceRoleCommand(reserved.id(), ACTOR);

    assertThatExceptionOfType(CannotDeleteReservedWorkspaceRoleException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).deleteById(any());
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

  @Test
  void rejectsDeletingARoleThatIsStillAnotherRolesParent() {
    WorkspaceRole childRole = WorkspaceRole.define(organizationId, "Child", role.id(), Set.of());
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role, childRole));
    DeleteWorkspaceRoleCommand command = new DeleteWorkspaceRoleCommand(role.id(), ACTOR);

    assertThatExceptionOfType(WorkspaceRoleHasChildRolesException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).deleteById(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  // Live UX request, 2026-09-28: when a workspaceId is given, every holder WITHIN that Workspace
  // is auto-unassigned first, instead of unconditionally blocking the delete.
  @Test
  void doesNotAttemptToUnassignAnyoneWhenNoWorkspaceIdIsGiven() {
    DeleteWorkspaceRoleCommand command = new DeleteWorkspaceRoleCommand(role.id(), ACTOR);

    service.handle(command);

    verify(memberships, never()).findAllByWorkspaceId(any());
    verify(memberships, never()).save(any());
  }

  @Test
  void unassignsEveryHolderWithinTheGivenWorkspaceThenDeletesTheRole() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    WorkspaceMembership holder = WorkspaceMembership.join(workspaceId, accountId, role.id());
    when(memberships.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(holder));
    when(memberships.existsByRoleId(role.id())).thenReturn(false);
    DeleteWorkspaceRoleCommand command =
        new DeleteWorkspaceRoleCommand(role.id(), workspaceId, ACTOR);

    service.handle(command);

    ArgumentCaptor<WorkspaceMembership> saved = ArgumentCaptor.forClass(WorkspaceMembership.class);
    verify(memberships).save(saved.capture());
    assertThat(saved.getValue().id()).isEqualTo(holder.id());
    assertThat(saved.getValue().roleId()).isNull();
    verify(roles).deleteById(role.id());
  }

  // The role is still held in a DIFFERENT Workspace (roles are Organization-scoped and shared,
  // ADR-0027/0028) — this Workspace's own holder is still cleared, but the org-wide check still
  // blocks the actual delete.
  @Test
  void stillBlocksDeletionWhenTheRoleIsAlsoAssignedInADifferentWorkspace() {
    UUID workspaceId = UUID.randomUUID();
    when(memberships.findAllByWorkspaceId(workspaceId)).thenReturn(List.of());
    when(memberships.existsByRoleId(role.id())).thenReturn(true);
    DeleteWorkspaceRoleCommand command =
        new DeleteWorkspaceRoleCommand(role.id(), workspaceId, ACTOR);

    assertThatExceptionOfType(WorkspaceRoleStillAssignedException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).deleteById(any());
  }

  // Bulk-clearing every holder of this role must not leave the Workspace with zero manage_members
  // holders — same ManageMembersGuard protection every other role-changing action already applies.
  @Test
  void refusesToUnassignTheLastManageMembersHolder() {
    UUID workspaceId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    WorkspaceRole manageMembersRole =
        WorkspaceRole.define(
            organizationId, "Owner", null, Set.of(ReservedWorkspacePermissions.MANAGE_MEMBERS));
    WorkspaceMembership onlyHolder =
        WorkspaceMembership.join(workspaceId, accountId, manageMembersRole.id());
    when(roles.findById(manageMembersRole.id())).thenReturn(Optional.of(manageMembersRole));
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(manageMembersRole));
    when(memberships.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(onlyHolder));
    DeleteWorkspaceRoleCommand command =
        new DeleteWorkspaceRoleCommand(manageMembersRole.id(), workspaceId, ACTOR);

    assertThatExceptionOfType(CannotDemoteLastAdminException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
    verify(roles, never()).deleteById(any());
  }
}
