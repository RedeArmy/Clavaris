package com.clavaris.organization.application.usecase.deleteworkspacerole;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceRoleDeletedEvent;
import com.clavaris.organization.domain.model.WorkspaceRole;

/** Orchestration for {@link DeleteWorkspaceRoleUseCase}. */
public class DeleteWorkspaceRoleService implements DeleteWorkspaceRoleUseCase {

  private final WorkspaceRoleRepository roles;
  private final WorkspaceMembershipRepository memberships;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public DeleteWorkspaceRoleService(
      final WorkspaceRoleRepository roles,
      final WorkspaceMembershipRepository memberships,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.roles = roles;
    this.memberships = memberships;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  public void handle(final DeleteWorkspaceRoleCommand command) {
    final WorkspaceRole role =
        roles
            .findById(command.roleId())
            .orElseThrow(() -> new WorkspaceRoleNotFoundException(command.roleId()));

    if (role.reserved()) {
      throw new CannotDeleteReservedWorkspaceRoleException(command.roleId());
    }
    if (memberships.existsByRoleId(command.roleId())) {
      throw new WorkspaceRoleStillAssignedException(command.roleId());
    }
    // workspace_roles.parent_role_id has no ON DELETE action (see that migration's own comment) —
    // without this check, deleting a role that's still some other role's parent would instead
    // fail with a raw Postgres foreign-key violation, not this clean, typed rejection.
    final boolean hasChildRoles =
        roles.findAllByOrganizationId(role.organizationId()).stream()
            .anyMatch(candidate -> command.roleId().equals(candidate.parentRoleId()));
    if (hasChildRoles) {
      throw new WorkspaceRoleHasChildRolesException(command.roleId());
    }

    roles.deleteById(command.roleId());

    auditEvents.write(
        command.actor(),
        "workspace_role.deleted",
        "WorkspaceRole",
        role.id().toString(),
        "organizationId=" + role.organizationId());

    outbox.write(
        "WorkspaceRole",
        "workspace_role.deleted",
        role.id(),
        role.organizationId(),
        WorkspaceRoleDeletedEvent.of(role.id(), role.organizationId()));
  }
}
