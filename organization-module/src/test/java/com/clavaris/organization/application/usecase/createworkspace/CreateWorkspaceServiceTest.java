package com.clavaris.organization.application.usecase.createworkspace;

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
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.model.Workspace;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateWorkspaceServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceRepository workspaces;
  private OrganizationRepository organizations;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private CreateWorkspaceService service;

  @BeforeEach
  void setUp() {
    workspaces = mock(WorkspaceRepository.class);
    organizations = mock(OrganizationRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    when(organizations.existsById(any())).thenReturn(true);
    service = new CreateWorkspaceService(workspaces, organizations, auditEvents, outbox);
  }

  @Test
  void createsAndPersistsTheWorkspace() {
    UUID organizationId = UUID.randomUUID();

    Workspace workspace =
        service.handle(new CreateWorkspaceCommand(organizationId, "Engineering", ACTOR));

    assertThat(workspace.name()).isEqualTo("Engineering");
    assertThat(workspace.organizationId()).isEqualTo(organizationId);
    verify(workspaces).save(workspace);
  }

  @Test
  void recordsAnAuditEventAndAnOutboxEvent() {
    Workspace workspace =
        service.handle(new CreateWorkspaceCommand(UUID.randomUUID(), "Engineering", ACTOR));

    verify(auditEvents)
        .write(
            eq(ACTOR),
            eq("workspace.created"),
            eq("Workspace"),
            eq(workspace.id().toString()),
            any());
    verify(outbox)
        .write(eq("Workspace"), eq("workspace.created"), eq(workspace.id()), any(), any());
  }

  @Test
  void rejectsAnUnknownOrganizationWithoutPersistingAnything() {
    UUID unknownOrganizationId = UUID.randomUUID();
    when(organizations.existsById(unknownOrganizationId)).thenReturn(false);
    CreateWorkspaceCommand command =
        new CreateWorkspaceCommand(unknownOrganizationId, "Engineering", ACTOR);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(workspaces, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }
}
