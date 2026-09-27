package com.clavaris.organization.application.usecase.createworkspace;

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
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class CreateWorkspaceServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceRepository workspaces;
  private WorkspaceRoleRepository roles;
  private OrganizationRepository organizations;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private CreateWorkspaceService service;

  @BeforeEach
  void setUp() {
    workspaces = mock(WorkspaceRepository.class);
    roles = mock(WorkspaceRoleRepository.class);
    organizations = mock(OrganizationRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    when(organizations.existsById(any())).thenReturn(true);
    when(roles.findAllByOrganizationId(any())).thenReturn(List.of());
    service = new CreateWorkspaceService(workspaces, roles, organizations, auditEvents, outbox);
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

  // ADR-0027 §2: every Organization needs its one reserved bootstrap role for the member-add
  // flow to have anything to offer.
  @Test
  void seedsTheReservedRoleWhenNoneExistsYetForThisOrganization() {
    UUID organizationId = UUID.randomUUID();

    service.handle(new CreateWorkspaceCommand(organizationId, "Engineering", ACTOR));

    ArgumentCaptor<WorkspaceRole> captor = ArgumentCaptor.forClass(WorkspaceRole.class);
    verify(roles).saveAndFlush(captor.capture());
    WorkspaceRole seeded = captor.getValue();
    assertThat(seeded.organizationId()).isEqualTo(organizationId);
    assertThat(seeded.reserved()).isTrue();
  }

  @Test
  void doesNotReseedTheReservedRoleWhenOneAlreadyExistsForThisOrganization() {
    UUID organizationId = UUID.randomUUID();
    when(roles.findAllByOrganizationId(organizationId))
        .thenReturn(List.of(WorkspaceRole.defineReserved(organizationId, "Admin")));

    service.handle(new CreateWorkspaceCommand(organizationId, "Engineering", ACTOR));

    verify(roles, never()).saveAndFlush(any());
  }

  // A concurrent first-Workspace creation for the same brand-new Organization could lose the
  // ux_workspace_roles_organization_id_name race — see CreateWorkspaceService's own Javadoc.
  // This must degrade, not propagate: the invariant (a reserved role exists) still holds, since
  // the winner's own saveAndFlush already committed it.
  @Test
  void toleratesLosingTheReservedRoleCreationRaceWithoutFailingTheWorkspaceCreation() {
    UUID organizationId = UUID.randomUUID();
    doThrow(new DataIntegrityViolationException("duplicate key")).when(roles).saveAndFlush(any());

    Workspace workspace =
        service.handle(new CreateWorkspaceCommand(organizationId, "Engineering", ACTOR));

    assertThat(workspace.name()).isEqualTo("Engineering");
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
    verify(roles, never()).saveAndFlush(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }
}
