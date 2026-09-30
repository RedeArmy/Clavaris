package com.clavaris.organization.application.usecase.updateworkspacerole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpdateWorkspaceRoleServiceTest {

  private static final AuditActor ACTOR = AuditActor.platformClient("test-client");

  private WorkspaceRoleRepository roles;
  private AuditEventRecorder auditEvents;
  private EventOutboxWriter outbox;
  private UpdateWorkspaceRoleService service;

  private UUID organizationId;
  private WorkspaceRole role;

  @BeforeEach
  void setUp() {
    roles = mock(WorkspaceRoleRepository.class);
    auditEvents = mock(AuditEventRecorder.class);
    outbox = mock(EventOutboxWriter.class);
    organizationId = UUID.randomUUID();
    role = WorkspaceRole.define(organizationId, "Supervisor", null, Set.of("a"));
    when(roles.findById(role.id())).thenReturn(Optional.of(role));
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role));
    service = new UpdateWorkspaceRoleService(roles, auditEvents, outbox);
  }

  @Test
  void renamesAndChangesPermissions() {
    WorkspaceRole updated =
        service.handle(
            new UpdateWorkspaceRoleCommand(
                role.id(), organizationId, "Renamed", null, Set.of("b"), ACTOR));

    assertThat(updated.name()).isEqualTo("Renamed");
    assertThat(updated.permissions()).containsExactly("b");
    verify(roles).save(updated);
  }

  @Test
  void recordsAnAuditEventAndAnOutboxEvent() {
    WorkspaceRole updated =
        service.handle(
            new UpdateWorkspaceRoleCommand(
                role.id(), organizationId, "Renamed", null, Set.of(), ACTOR));

    verify(auditEvents)
        .write(
            org.mockito.ArgumentMatchers.eq(ACTOR),
            org.mockito.ArgumentMatchers.eq("workspace_role.updated"),
            org.mockito.ArgumentMatchers.eq("WorkspaceRole"),
            org.mockito.ArgumentMatchers.eq(updated.id().toString()),
            any());
    verify(outbox)
        .write(
            org.mockito.ArgumentMatchers.eq("WorkspaceRole"),
            org.mockito.ArgumentMatchers.eq("workspace_role.updated"),
            org.mockito.ArgumentMatchers.eq(updated.id()),
            org.mockito.ArgumentMatchers.eq(organizationId),
            any());
  }

  @Test
  void rejectsAnUnknownRoleId() {
    UUID unknownRoleId = UUID.randomUUID();
    when(roles.findById(unknownRoleId)).thenReturn(Optional.empty());
    UpdateWorkspaceRoleCommand command =
        new UpdateWorkspaceRoleCommand(
            unknownRoleId, organizationId, "Renamed", null, Set.of(), ACTOR);

    assertThatExceptionOfType(WorkspaceRoleNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).save(any());
  }

  // TD-SEC-056: the role genuinely exists — just for a different Organization than the command
  // claims. Must 404 identically to an unknown roleId, not silently mutate another tenant's role.
  @Test
  void rejectsARoleBelongingToADifferentOrganization() {
    UUID otherOrganizationId = UUID.randomUUID();
    UpdateWorkspaceRoleCommand command =
        new UpdateWorkspaceRoleCommand(
            role.id(), otherOrganizationId, "Renamed", null, Set.of(), ACTOR);

    assertThatExceptionOfType(WorkspaceRoleNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).save(any());
  }

  @Test
  void rejectsRenamingToAnAlreadyUsedNameWithinTheSameOrganization() {
    WorkspaceRole other = WorkspaceRole.define(organizationId, "Taken", null, Set.of());
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role, other));
    UpdateWorkspaceRoleCommand command =
        new UpdateWorkspaceRoleCommand(role.id(), organizationId, "Taken", null, Set.of(), ACTOR);

    assertThatExceptionOfType(DuplicateWorkspaceRoleNameException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).save(any());
  }

  @Test
  void allowsKeepingItsOwnCurrentNameUnchanged() {
    WorkspaceRole updated =
        service.handle(
            new UpdateWorkspaceRoleCommand(
                role.id(), organizationId, role.name(), null, Set.of("x"), ACTOR));

    assertThat(updated.name()).isEqualTo(role.name());
  }

  @Test
  void rejectsAnUnknownParentRoleId() {
    UUID unknownParentId = UUID.randomUUID();
    UpdateWorkspaceRoleCommand command =
        new UpdateWorkspaceRoleCommand(
            role.id(), organizationId, role.name(), unknownParentId, Set.of(), ACTOR);

    assertThatExceptionOfType(WorkspaceRoleNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).save(any());
  }

  @Test
  void rejectsAParentRoleIdThatWouldCreateACycle() {
    WorkspaceRole child = WorkspaceRole.define(organizationId, "Child", role.id(), Set.of());
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(role, child));
    when(roles.findById(role.id())).thenReturn(Optional.of(role));
    // Setting role's own parent to child would create role -> child -> role.
    UpdateWorkspaceRoleCommand command =
        new UpdateWorkspaceRoleCommand(
            role.id(), organizationId, role.name(), child.id(), Set.of("a"), ACTOR);

    assertThatExceptionOfType(WorkspaceRoleCycleException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).save(any());
  }

  @Test
  void allowsSettingTheSameParentRoleIdItAlreadyHas() {
    WorkspaceRole parent = WorkspaceRole.define(organizationId, "Parent", null, Set.of());
    WorkspaceRole child = WorkspaceRole.define(organizationId, "Child", parent.id(), Set.of("a"));
    when(roles.findById(child.id())).thenReturn(Optional.of(child));
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(parent, child));

    WorkspaceRole updated =
        service.handle(
            new UpdateWorkspaceRoleCommand(
                child.id(), organizationId, child.name(), parent.id(), Set.of("b"), ACTOR));

    assertThat(updated.parentRoleId()).isEqualTo(parent.id());
  }

  @Test
  void rejectsStrippingTheReservedRolesReservedPermissions() {
    WorkspaceRole reserved = WorkspaceRole.defineReserved(organizationId, "Admin");
    when(roles.findById(reserved.id())).thenReturn(Optional.of(reserved));
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(reserved));
    UpdateWorkspaceRoleCommand command =
        new UpdateWorkspaceRoleCommand(
            reserved.id(), organizationId, reserved.name(), null, Set.of(), ACTOR);

    assertThatExceptionOfType(CannotStripReservedWorkspaceRolePermissionsException.class)
        .isThrownBy(() -> service.handle(command));

    verify(roles, never()).save(any());
  }

  @Test
  void allowsRenamingTheReservedRoleWhileKeepingItsReservedPermissions() {
    WorkspaceRole reserved = WorkspaceRole.defineReserved(organizationId, "Admin");
    when(roles.findById(reserved.id())).thenReturn(Optional.of(reserved));
    when(roles.findAllByOrganizationId(organizationId)).thenReturn(List.of(reserved));
    UpdateWorkspaceRoleCommand command =
        new UpdateWorkspaceRoleCommand(
            reserved.id(), organizationId, "Renamed Admin", null, reserved.permissions(), ACTOR);

    WorkspaceRole updated = service.handle(command);

    assertThat(updated.name()).isEqualTo("Renamed Admin");
    assertThat(updated.reserved()).isTrue();
  }
}
