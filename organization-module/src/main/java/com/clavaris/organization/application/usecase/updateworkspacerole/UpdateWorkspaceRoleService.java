package com.clavaris.organization.application.usecase.updateworkspacerole;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.createworkspacerole.DuplicateWorkspaceRoleNameException;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceRoleUpdatedEvent;
import com.clavaris.organization.domain.model.ReservedWorkspacePermissions;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.service.WorkspaceRoleHierarchy;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link UpdateWorkspaceRoleUseCase}.
 *
 * <p>TD-ARCH-029 (SDE-III review, 2026-10-01): {@code @Transactional} below — this method's own
 * save + audit-event write + outbox write were three separate, unguarded steps, the same gap {@code
 * CreateWorkspaceRoleService}'s own TD-ARCH-027 (closed) already fixed for its sibling; a failure
 * after the role row already committed would leave a silent, permanent audit/outbox gap.
 */
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
  @Transactional
  public WorkspaceRole handle(final UpdateWorkspaceRoleCommand command) {
    // TD-SEC-056: same anti-enumeration filter DeleteWorkspaceRoleService's own identical fix
    // applies.
    final WorkspaceRole existing =
        roles
            .findById(command.roleId())
            .filter(candidate -> candidate.organizationId().equals(command.organizationId()))
            .orElseThrow(() -> new WorkspaceRoleNotFoundException(command.roleId()));

    final Map<UUID, WorkspaceRole> rolesById =
        roles.findAllByOrganizationId(existing.organizationId()).stream()
            .collect(Collectors.toMap(WorkspaceRole::id, Function.identity()));

    requireNameNotTaken(existing, command, rolesById);
    requireValidParentRoleId(existing, command, rolesById);
    requirePermissionsKeepReservedSet(existing, command);

    final WorkspaceRole updated =
        existing
            .withName(command.name())
            .withParentRoleId(command.parentRoleId())
            .withPermissions(command.permissions());
    persistOrThrowOnDuplicate(updated, command.name());

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

  // TD-ARCH-029: saveAndFlush not save — requireNameNotTaken above is a fast-path pre-check only,
  // not the real safety mechanism (same caveat CreateWorkspaceRoleService's own identical fix
  // documents) — the real one is ux_workspace_roles_organization_id_name, which must fire
  // synchronously inside this try, not deferred to the transaction's own commit.
  private void persistOrThrowOnDuplicate(final WorkspaceRole role, final String name) {
    try {
      roles.saveAndFlush(role);
    } catch (final DataIntegrityViolationException raceLost) {
      throw new DuplicateWorkspaceRoleNameException(name, raceLost);
    }
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

  // Raised here, ahead of WorkspaceRole#withPermissions's own identical (but untyped) guard —
  // see CannotStripReservedWorkspaceRolePermissionsException's own Javadoc for why this matters
  // for the REST admin API specifically, not just the dashboard.
  private void requirePermissionsKeepReservedSet(
      final WorkspaceRole existing, final UpdateWorkspaceRoleCommand command) {
    if (existing.reserved()
        && !command.permissions().containsAll(ReservedWorkspacePermissions.ALL)) {
      throw new CannotStripReservedWorkspaceRolePermissionsException(command.roleId());
    }
  }
}
