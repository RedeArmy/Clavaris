package com.clavaris.organization.application.usecase.addworkspacemember;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class AddWorkspaceMemberServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceRepository workspaces;
  private WorkspaceMembershipRepository memberships;
  private AccountProvisioner accountProvisioner;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private AddWorkspaceMemberService service;

  private Workspace workspace;

  @BeforeEach
  void setUp() {
    workspaces = mock(WorkspaceRepository.class);
    memberships = mock(WorkspaceMembershipRepository.class);
    accountProvisioner = mock(AccountProvisioner.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);

    workspace = Workspace.register(UUID.randomUUID(), "Engineering");
    when(workspaces.findById(workspace.id())).thenReturn(Optional.of(workspace));

    // A real TransactionTemplate would need a real PlatformTransactionManager/DataSource — this
    // fake just runs the callback immediately, same effect for a unit test that never touches a
    // real database (JpaWorkspaceMembershipRepositoryTest is where the real transactional
    // behaviour is proven, against real Postgres).
    PlatformTransactionManager fakeTransactionManager = mock(PlatformTransactionManager.class);
    TransactionTemplate fakeTransactionTemplate =
        new TransactionTemplate(fakeTransactionManager) {
          @Override
          public <T> T execute(final TransactionCallback<T> action) {
            TransactionStatus status = new SimpleTransactionStatus();
            return action.doInTransaction(status);
          }
        };

    service =
        new AddWorkspaceMemberService(
            workspaces,
            memberships,
            accountProvisioner,
            auditEvents,
            outbox,
            fakeTransactionTemplate);
  }

  @Test
  void provisionsAnAccountAndSavesTheMembership() {
    UUID accountId = UUID.randomUUID();
    when(accountProvisioner.provisionAndSendWelcome(workspace.organizationId(), "new@example.com"))
        .thenReturn(new AccountProvisioner.ProvisionedAccount(accountId));

    WorkspaceMembership membership =
        service.handle(
            new AddWorkspaceMemberCommand(
                workspace.id(), "new@example.com", WorkspaceRole.MEMBER, ACTOR));

    assertThat(membership.workspaceId()).isEqualTo(workspace.id());
    assertThat(membership.accountId()).isEqualTo(accountId);
    assertThat(membership.role()).isEqualTo(WorkspaceRole.MEMBER);
    verify(memberships).save(membership);
  }

  @Test
  void recordsAnAuditEventAndAnOutboxEvent() {
    UUID accountId = UUID.randomUUID();
    when(accountProvisioner.provisionAndSendWelcome(any(), any()))
        .thenReturn(new AccountProvisioner.ProvisionedAccount(accountId));

    WorkspaceMembership membership =
        service.handle(
            new AddWorkspaceMemberCommand(
                workspace.id(), "new@example.com", WorkspaceRole.ADMIN, ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("workspace_membership.added"),
            eq("WorkspaceMembership"),
            eq(membership.id().toString()),
            any());
    verify(outbox)
        .write(
            eq("WorkspaceMembership"),
            eq("workspace_membership.added"),
            eq(membership.id()),
            any());
  }

  @Test
  void rejectsAnUnknownWorkspaceWithoutProvisioningAnything() {
    UUID unknownWorkspaceId = UUID.randomUUID();
    when(workspaces.findById(unknownWorkspaceId)).thenReturn(Optional.empty());
    AddWorkspaceMemberCommand command =
        new AddWorkspaceMemberCommand(
            unknownWorkspaceId, "new@example.com", WorkspaceRole.MEMBER, ACTOR);

    assertThatExceptionOfType(WorkspaceNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verifyNoInteractions(accountProvisioner);
    verify(memberships, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  // BR-WS-04: this port's own AccountAlreadyExistsException must propagate unchanged, never
  // swallowed or translated into something else — the controller is what maps it to 409.
  @Test
  void letsAccountAlreadyExistsExceptionPropagateWithoutSavingAMembership() {
    when(accountProvisioner.provisionAndSendWelcome(any(), any()))
        .thenThrow(
            new AccountProvisioner.AccountAlreadyExistsException(
                workspace.organizationId(), "taken@example.com"));
    AddWorkspaceMemberCommand command =
        new AddWorkspaceMemberCommand(
            workspace.id(), "taken@example.com", WorkspaceRole.MEMBER, ACTOR);

    assertThatExceptionOfType(AccountProvisioner.AccountAlreadyExistsException.class)
        .isThrownBy(() -> service.handle(command));

    verify(memberships, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  // TD-WS-001: the compensating-action saga — a DB failure right after the Account is provisioned
  // must not leave a permanent orphan.
  @Test
  void deprovisionsTheAccountAndRethrowsTheOriginalFailureWhenTheMembershipWriteFails() {
    UUID accountId = UUID.randomUUID();
    when(accountProvisioner.provisionAndSendWelcome(any(), any()))
        .thenReturn(new AccountProvisioner.ProvisionedAccount(accountId));
    RuntimeException membershipFailure = new RuntimeException("membership save failed");
    doThrow(membershipFailure).when(memberships).save(any());
    AddWorkspaceMemberCommand command =
        new AddWorkspaceMemberCommand(
            workspace.id(), "new@example.com", WorkspaceRole.MEMBER, ACTOR);

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(() -> service.handle(command))
        .isSameAs(membershipFailure);

    verify(accountProvisioner).deprovision(accountId, ACTOR);
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  // The compensating action itself failing must never mask the original failure that triggered
  // it — a double failure is exactly today's pre-existing manual-remediation fallback, not a
  // worse or different outcome than before this saga existed.
  @Test
  void stillRethrowsTheOriginalFailureWhenTheCompensatingDeprovisionAlsoFails() {
    UUID accountId = UUID.randomUUID();
    when(accountProvisioner.provisionAndSendWelcome(any(), any()))
        .thenReturn(new AccountProvisioner.ProvisionedAccount(accountId));
    RuntimeException membershipFailure = new RuntimeException("membership save failed");
    doThrow(membershipFailure).when(memberships).save(any());
    doThrow(new RuntimeException("deprovision also failed"))
        .when(accountProvisioner)
        .deprovision(any(), any());
    AddWorkspaceMemberCommand command =
        new AddWorkspaceMemberCommand(
            workspace.id(), "new@example.com", WorkspaceRole.MEMBER, ACTOR);

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(() -> service.handle(command))
        .isSameAs(membershipFailure);
  }
}
