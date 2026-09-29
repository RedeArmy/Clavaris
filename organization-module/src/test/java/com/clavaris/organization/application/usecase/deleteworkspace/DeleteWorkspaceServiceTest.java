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
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceDeletedEvent;
import com.clavaris.organization.domain.model.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeleteWorkspaceServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformAccount(UUID.randomUUID());

  private WorkspaceRepository workspaces;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private DeleteWorkspaceService service;

  private Workspace workspace;

  @BeforeEach
  void setUp() {
    workspaces = mock(WorkspaceRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    workspace = Workspace.register(UUID.randomUUID(), "Engineering");
    when(workspaces.findById(workspace.id())).thenReturn(Optional.of(workspace));
    service = new DeleteWorkspaceService(workspaces, auditEvents, outbox);
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
