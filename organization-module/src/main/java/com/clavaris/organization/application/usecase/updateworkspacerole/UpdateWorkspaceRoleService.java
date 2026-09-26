package com.clavaris.organization.application.usecase.updateworkspacerole;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceRoleUpdatedEvent;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.service.WorkspaceRoleHierarchy;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Orchestration for {@link UpdateWorkspaceRoleUseCase}. */
public class UpdateWorkspaceRoleService implements UpdateWorkspaceRoleUseCase {

  private final WorkspaceRoleRepository roles;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public UpdateWorkspaceRoleService(
      final WorkspaceRoleRepository roles,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.roles = roles;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  public WorkspaceRole handle(final UpdateWorkspaceRoleCommand command) {
    final WorkspaceRole existing =
        roles
            .findById(command.roleId())
            .orElseThrow(() -> new WorkspaceRoleNotFoundException(command.roleId()));

    final Map<UUID, WorkspaceRole> rolesById =
        roles.findAllByOrganizationId(existing.organizationId()).stream()
            .collect(Collectors.toMap(WorkspaceRole::id, Function.identity()));

    requireNameNotTaken(existing, command, rolesById);
    requireValidParentRoleId(existing, command, rolesById);

    final WorkspaceRole updated =
        existing
            .withName(command.name())
            .withParentRoleId(command.parentRoleId())
            .withPermissions(command.permissions());
    roles.save(updated);

    auditEvents.write(
        command.actor(),
        "workspace_role.updated",
        "WorkspaceRole",
        updated.id().toString(),
        "name=" + updated.name());

    outbox.write(
        "WorkspaceRole",
        "workspace_role.updated",
        updated.id(),
        updated.organizationId(),
        WorkspaceRoleUpdatedEvent.from(updated));

    return updated;
  }

  private void requireNameNotTaken(
      final WorkspaceRole existing,
      final UpdateWorkspaceRoleCommand command,
      final Map<UUID, WorkspaceRole> rolesById) {
    final boolean nameChanged = !existing.name().equals(command.name());
    final boolean nameTaken =
        rolesById.values().stream()
            .anyMatch(
                role -> !role.id().equals(command.roleId()) && role.name().equals(command.name()));
    if (nameChanged && nameTaken) {
      throw new DuplicateWorkspaceRoleNameException(command.name());
    }
  }

  private void requireValidParentRoleId(
      final WorkspaceRole existing,
      final UpdateWorkspaceRoleCommand command,
      final Map<UUID, WorkspaceRole> rolesById) {
    final UUID newParentRoleId = command.parentRoleId();
    final boolean parentUnchanged =
        newParentRoleId == null || newParentRoleId.equals(existing.parentRoleId());
    if (parentUnchanged) {
      return;
    }
    if (!rolesById.containsKey(newParentRoleId)) {
      throw new WorkspaceRoleNotFoundException(newParentRoleId);
    }
    if (WorkspaceRoleHierarchy.wouldCreateCycle(command.roleId(), newParentRoleId, rolesById)) {
      throw new WorkspaceRoleCycleException(command.roleId(), newParentRoleId);
    }
  }
}
