package com.clavaris.organization.application.usecase.assignworkspaceroletoaccount;

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
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.CannotDemoteLastAdminException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssignWorkspaceRoleToAccountServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceRepository workspaces;
  private WorkspaceRoleRepository roles;
  private WorkspaceMembershipRepository memberships;
  private OrganizationAccountDirectory accountDirectory;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private AssignWorkspaceRoleToAccountService service;

  private Workspace workspace;
  private WorkspaceRole manageMembersRole;
  private WorkspaceRole plainRole;
  private UUID accountId;

  @BeforeEach
  void setUp() {
    workspaces = mock(WorkspaceRepository.class);
    roles = mock(WorkspaceRoleRepository.class);
    memberships = mock(WorkspaceMembershipRepository.class);
    accountDirectory = mock(OrganizationAccountDirectory.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    service =
        new AssignWorkspaceRoleToAccountService(
            workspaces, roles, memberships, accountDirectory, auditEvents, outbox);

    workspace = Workspace.register(UUID.randomUUID(), "Engineering");
    manageMembersRole = WorkspaceRole.defineReserved(workspace.organizationId(), "Admin");
    plainRole = WorkspaceRole.define(workspace.organizationId(), "Member", null, Set.of());
    accountId = UUID.randomUUID();

    when(workspaces.findById(workspace.id())).thenReturn(Optional.of(workspace));
    when(roles.findById(manageMembersRole.id())).thenReturn(Optional.of(manageMembersRole));
    when(roles.findById(plainRole.id())).thenReturn(Optional.of(plainRole));
    when(roles.findAllByOrganizationId(workspace.organizationId()))
        .thenReturn(List.of(manageMembersRole, plainRole));
    when(accountDirectory.listAccountsForOrganization(workspace.organizationId()))
        .thenReturn(List.of(new OrganizationAccountSummary(accountId, "ada@example.com")));
    when(memberships.findByWorkspaceIdAndAccountId(workspace.id(), accountId))
        .thenReturn(Optional.empty());
  }

  @Test
  void createsANewMembershipWhenTheAccountHasNoneYet() {
    WorkspaceMembership created =
        service.handle(
            new AssignWorkspaceRoleToAccountCommand(
                workspace.id(), accountId, plainRole.id(), ACTOR));

    assertThat(created.workspaceId()).isEqualTo(workspace.id());
    assertThat(created.accountId()).isEqualTo(accountId);
    assertThat(created.roleId()).isEqualTo(plainRole.id());
    verify(memberships).save(created);
    verify(auditEvents)
        .write(
            eq(ACTOR), eq("workspace_membership.added"), eq("WorkspaceMembership"), any(), any());
    verify(outbox)
        .write(eq("WorkspaceMembership"), eq("workspace_membership.added"), any(), any(), any());
  }

  @Test
  void replacesTheRoleWhenTheAccountAlreadyHasAMembership() {
    WorkspaceMembership existing =
        WorkspaceMembership.join(workspace.id(), accountId, manageMembersRole.id());
    when(memberships.findByWorkspaceIdAndAccountId(workspace.id(), accountId))
        .thenReturn(Optional.of(existing));
    when(memberships.findAllByWorkspaceId(workspace.id()))
        .thenReturn(
            List.of(
                existing,
                WorkspaceMembership.join(
                    workspace.id(), UUID.randomUUID(), manageMembersRole.id())));

    WorkspaceMembership updated =
        service.handle(
            new AssignWorkspaceRoleToAccountCommand(
                workspace.id(), accountId, plainRole.id(), ACTOR));

    assertThat(updated.id()).isEqualTo(existing.id());
    assertThat(updated.roleId()).isEqualTo(plainRole.id());
    verify(memberships).save(updated);
    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("workspace_membership.role_changed"),
            eq("WorkspaceMembership"),
            any(),
            any());
  }

  @Test
  void rejectsDemotingTheLastManageMembersHolderWithoutSavingAnything() {
    WorkspaceMembership existing =
        WorkspaceMembership.join(workspace.id(), accountId, manageMembersRole.id());
    when(memberships.findByWorkspaceIdAndAccountId(workspace.id(), accountId))
        .thenReturn(Optional.of(existing));
    when(memberships.findAllByWorkspaceId(workspace.id())).thenReturn(List.of(existing));
    AssignWorkspaceRoleToAccountCommand command =
        new AssignWorkspaceRoleToAccountCommand(workspace.id(), accountId, plainRole.id(), ACTOR);

    assertThatExceptionOfType(CannotDemoteLastAdminException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  @Test
  void rejectsAnUnknownWorkspace() {
    when(workspaces.findById(any())).thenReturn(Optional.empty());
    AssignWorkspaceRoleToAccountCommand command =
        new AssignWorkspaceRoleToAccountCommand(
            UUID.randomUUID(), accountId, plainRole.id(), ACTOR);

    assertThatExceptionOfType(WorkspaceNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
  }

  @Test
  void rejectsARoleBelongingToADifferentOrganization() {
    WorkspaceRole foreignRole = WorkspaceRole.define(UUID.randomUUID(), "Foreign", null, Set.of());
    when(roles.findById(foreignRole.id())).thenReturn(Optional.of(foreignRole));
    AssignWorkspaceRoleToAccountCommand command =
        new AssignWorkspaceRoleToAccountCommand(workspace.id(), accountId, foreignRole.id(), ACTOR);

    assertThatExceptionOfType(WorkspaceRoleNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
  }

  // Anti-cross-tenant: a submitted accountId that the directory doesn't return for this
  // Organization must never create a cross-tenant WorkspaceMembership — see
  // AccountNotInOrganizationException's own Javadoc.
  @Test
  void rejectsAnAccountThatDoesNotBelongToThisOrganization() {
    when(accountDirectory.listAccountsForOrganization(workspace.organizationId()))
        .thenReturn(List.of());
    AssignWorkspaceRoleToAccountCommand command =
        new AssignWorkspaceRoleToAccountCommand(workspace.id(), accountId, plainRole.id(), ACTOR);

    assertThatExceptionOfType(AccountNotInOrganizationException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
  }
}
