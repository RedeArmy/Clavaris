package com.clavaris.organization.application.usecase.deleteworkspacerole;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceRoleDeletedEvent;
import com.clavaris.organization.domain.model.ReservedWorkspacePermissions;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.service.WorkspaceRoleHierarchy;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    final Map<UUID, WorkspaceRole> rolesById =
        roles.findAllByOrganizationId(role.organizationId()).stream()
            .collect(Collectors.toMap(WorkspaceRole::id, Function.identity()));

    // ADR-0028 §3: the reserved role is no longer permanently undeletable — a tenant may delete
    // it once some other role already carries both reserved permissions in its own effective set,
    // so the Organization never loses its own path to self-governance. Checks role definitions
    // only, not real membership coverage of the substitute — see that ADR's own open-question 2
    // for why that narrower check was the one confirmed.
    if (role.reserved() && !hasSubstituteReservedRole(command.roleId(), rolesById)) {
      throw new CannotDeleteReservedWorkspaceRoleException(command.roleId());
    }
    if (memberships.existsByRoleId(command.roleId())) {
      throw new WorkspaceRoleStillAssignedException(command.roleId());
    }
    // workspace_roles.parent_role_id has no ON DELETE action (see that migration's own comment) —
    // without this check, deleting a role that's still some other role's parent would instead
    // fail with a raw Postgres foreign-key violation, not this clean, typed rejection.
    final boolean hasChildRoles =
        rolesById.values().stream()
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

  private static boolean hasSubstituteReservedRole(
      final UUID roleId, final Map<UUID, WorkspaceRole> rolesById) {
    return rolesById.values().stream()
        .filter(candidate -> !candidate.id().equals(roleId))
        .anyMatch(
            candidate ->
                WorkspaceRoleHierarchy.effectivePermissions(candidate.id(), rolesById)
                    .containsAll(ReservedWorkspacePermissions.ALL));
  }
}
