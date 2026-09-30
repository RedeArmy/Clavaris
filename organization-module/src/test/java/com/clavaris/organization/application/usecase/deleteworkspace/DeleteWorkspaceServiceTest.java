package com.clavaris.organization.application.usecase.deleteworkspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.application.usecase.removeworkspacemember.WorkspaceMemberAccountRevoker;
import com.clavaris.organization.application.usecase.removeworkspacemember.WorkspaceMemberRefreshTokenRevoker;
import com.clavaris.organization.domain.event.WorkspaceDeletedEvent;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeleteWorkspaceServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private WorkspaceRepository workspaces;
  private WorkspaceMembershipRepository memberships;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private WorkspaceMemberRefreshTokenRevoker refreshTokenRevoker;
  private WorkspaceMemberAccountRevoker accountRevoker;
  private DeleteWorkspaceService service;

  private Workspace workspace;

  @BeforeEach
  void setUp() {
    workspaces = mock(WorkspaceRepository.class);
    memberships = mock(WorkspaceMembershipRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    refreshTokenRevoker = mock(WorkspaceMemberRefreshTokenRevoker.class);
    accountRevoker = mock(WorkspaceMemberAccountRevoker.class);
    workspace = Workspace.register(UUID.randomUUID(), "Engineering");
    when(workspaces.findById(workspace.id())).thenReturn(Optional.of(workspace));
    when(memberships.findAllByWorkspaceId(workspace.id())).thenReturn(List.of());
    service =
        new DeleteWorkspaceService(
            workspaces, memberships, auditEvents, outbox, refreshTokenRevoker, accountRevoker);
  }

  @Test
  void deletesTheWorkspaceRow() {
    service.handle(new DeleteWorkspaceCommand(workspace.id(), ACTOR));

    verify(workspaces).deleteById(workspace.id());
  }

  @Test
  void recordsAnAuditEvent() {
    service.handle(new DeleteWorkspaceCommand(workspace.id(), ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("workspace.deleted"),
            eq("Workspace"),
            eq(workspace.id().toString()),
            any());
  }

  @Test
  void writesAWorkspaceDeletedOutboxEvent() {
    service.handle(new DeleteWorkspaceCommand(workspace.id(), ACTOR));

    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(outbox)
        .write(
            eq("Workspace"),
            eq("workspace.deleted"),
            eq(workspace.id()),
            eq(workspace.organizationId()),
            payload.capture());
    assertThat(payload.getValue()).isInstanceOf(WorkspaceDeletedEvent.class);
    WorkspaceDeletedEvent event = (WorkspaceDeletedEvent) payload.getValue();
    assertThat(event.workspaceId()).isEqualTo(workspace.id());
    assertThat(event.organizationId()).isEqualTo(workspace.organizationId());
    assertThat(event.name()).isEqualTo(workspace.name());
  }

  // TD-WS-004: deleting a Workspace is a strictly larger membership-loss event than removing one
  // member — it must not get a weaker revocation guarantee than that single-member path.
  @Test
  void revokesEveryMembersRefreshTokensAndAccountAccessOnSuccess() {
    UUID firstAccountId = UUID.randomUUID();
    UUID secondAccountId = UUID.randomUUID();
    when(memberships.findAllByWorkspaceId(workspace.id()))
        .thenReturn(
            List.of(
                WorkspaceMembership.join(workspace.id(), firstAccountId, UUID.randomUUID()),
                WorkspaceMembership.join(workspace.id(), secondAccountId, UUID.randomUUID())));

    service.handle(new DeleteWorkspaceCommand(workspace.id(), ACTOR));

    verify(refreshTokenRevoker).revokeAllRefreshTokensFor(firstAccountId);
    verify(refreshTokenRevoker).revokeAllRefreshTokensFor(secondAccountId);
    verify(accountRevoker).revokeAllAccessFor(firstAccountId);
    verify(accountRevoker).revokeAllAccessFor(secondAccountId);
  }

  @Test
  void revokesNothingWhenTheWorkspaceHasNoMembers() {
    service.handle(new DeleteWorkspaceCommand(workspace.id(), ACTOR));

    verify(refreshTokenRevoker, never()).revokeAllRefreshTokensFor(any());
    verify(accountRevoker, never()).revokeAllAccessFor(any());
  }

  @Test
  void rejectsAnUnknownWorkspaceId() {
    UUID unknownWorkspaceId = UUID.randomUUID();
    when(workspaces.findById(unknownWorkspaceId)).thenReturn(Optional.empty());
    DeleteWorkspaceCommand command = new DeleteWorkspaceCommand(unknownWorkspaceId, ACTOR);

    assertThatExceptionOfType(WorkspaceNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(workspaces, never()).deleteById(any());
  }
}
