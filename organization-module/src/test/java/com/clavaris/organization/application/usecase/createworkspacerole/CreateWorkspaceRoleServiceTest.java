package com.clavaris.organization.application.usecase.createworkspacerole;

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
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createworkspace.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateWorkspaceRoleServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceRoleRepository roles;
  private OrganizationRepository organizations;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private CreateWorkspaceRoleService service;

  private UUID organizationId;

  @BeforeEach
  void setUp() {
    roles = mock(WorkspaceRoleRepository.class);
    organizations = mock(OrganizationRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    organizationId = UUID.randomUUID();
    when(organizations.existsById(organizationId)).thenReturn(true);
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of());
    service = new CreateWorkspaceRoleService(roles, organizations, auditEvents, outbox);
  }

  @Test
  void createsAndPersistsTheRole() {
    WorkspaceRole role =
        service.handle(
            new CreateWorkspaceRoleCommand(
                organizationId, "Supervisor", null, Set.of("org:posts:create"), ACTOR));

    assertThat(role.name()).isEqualTo("Supervisor");
    assertThat(role.organizationId()).isEqualTo(organizationId);
    assertThat(role.permissions()).containsExactly("org:posts:create");
    verify(roles).save(role);
  }

  @Test
  void recordsAnAuditEventAndAnOutboxEvent() {
    WorkspaceRole role =
        service.handle(
            new CreateWorkspaceRoleCommand(organizationId, "Supervisor", null, Set.of(), ACTOR));

    verify(auditEvents)
        .write(
            org.mockito.ArgumentMatchers.eq(ACTOR),
            org.mockito.ArgumentMatchers.eq("workspace_role.created"),
            org.mockito.ArgumentMatchers.eq("WorkspaceRole"),
            org.mockito.ArgumentMatchers.eq(role.id().toString()),
            any());
    verify(outbox)
        .write(
            org.mockito.ArgumentMatchers.eq("WorkspaceRole"),
            org.mockito.ArgumentMatchers.eq("workspace_role.created"),
            org.mockito.ArgumentMatchers.eq(role.id()),
            org.mockito.ArgumentMatchers.eq(organizationId),
            any());
  }

  @Test
  void rejectsAnUnknownOrganizationWithoutPersistingAnything() {
    UUID unknownOrganizationId = UUID.randomUUID();
    when(organizations.existsById(unknownOrganizationId)).thenReturn(false);
    CreateWorkspaceRoleCommand command =
        new CreateWorkspaceRoleCommand(unknownOrganizationId, "Supervisor", null, Set.of(), ACTOR);

    assertThatExceptionOfType(OrganizationNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).save(any());
  }

  @Test
  void rejectsADuplicateNameWithinTheSameOrganization() {
    WorkspaceRole existing = WorkspaceRole.define(organizationId, "Supervisor", null, Set.of());
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(existing));
    CreateWorkspaceRoleCommand command =
        new CreateWorkspaceRoleCommand(organizationId, "Supervisor", null, Set.of(), ACTOR);

    assertThatExceptionOfType(DuplicateWorkspaceRoleNameException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).save(any());
  }

  @Test
  void rejectsAnUnknownParentRoleIdWithoutPersistingAnything() {
    UUID unknownParentId = UUID.randomUUID();
    when(roles.findById(unknownParentId)).thenReturn(java.util.Optional.empty());
    CreateWorkspaceRoleCommand command =
        new CreateWorkspaceRoleCommand(
            organizationId, "Supervisor", unknownParentId, Set.of(), ACTOR);

    assertThatExceptionOfType(WorkspaceRoleNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).save(any());
    verifyNoInteractions(auditEvents);
    verifyNoInteractions(outbox);
  }

  @Test
  void rejectsAParentRoleIdBelongingToADifferentOrganization() {
    WorkspaceRole otherOrgRole =
        WorkspaceRole.define(UUID.randomUUID(), "Someone Else's Role", null, Set.of());
    when(roles.findById(otherOrgRole.id())).thenReturn(java.util.Optional.of(otherOrgRole));
    CreateWorkspaceRoleCommand command =
        new CreateWorkspaceRoleCommand(
            organizationId, "Supervisor", otherOrgRole.id(), Set.of(), ACTOR);

    assertThatExceptionOfType(WorkspaceRoleNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).save(any());
  }
}
