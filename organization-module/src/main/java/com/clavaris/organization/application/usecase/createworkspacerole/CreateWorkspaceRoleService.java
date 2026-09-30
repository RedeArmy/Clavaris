package com.clavaris.organization.application.usecase.createworkspacerole;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceRoleNotFoundException;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createworkspace.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceRoleCreatedEvent;
import com.clavaris.organization.domain.model.WorkspaceRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link CreateWorkspaceRoleUseCase}.
 *
 * <p>TD-ARCH-027 (closed): {@code @Transactional} below — this method's own save + audit-event
 * write + outbox write were three separate, unguarded steps; a failure after the role row already
 * committed would leave a silent, permanent audit/outbox gap, same shape TD-SEC-057 already fixed
 * for client-registry-module/webhook-module.
 */
public class CreateWorkspaceRoleService implements CreateWorkspaceRoleUseCase {

  private final WorkspaceRoleRepository roles;
  private final OrganizationRepository organizations;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public CreateWorkspaceRoleService(
      final WorkspaceRoleRepository roles,
      final OrganizationRepository organizations,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.roles = roles;
    this.organizations = organizations;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public WorkspaceRole handle(final CreateWorkspaceRoleCommand command) {
    if (!organizations.existsById(command.organizationId())) {
      throw new OrganizationNotFoundException(command.organizationId());
    }

    final boolean nameTaken =
        roles.findAllByOrganizationId(command.organizationId()).stream()
            .anyMatch(role -> role.name().equals(command.name()));
    if (nameTaken) {
      throw new DuplicateWorkspaceRoleNameException(command.name());
    }

    // ADR-0027 §1: a parentRoleId must belong to this same Organization — same anti-enumeration
    // posture WorkspaceRoleNotFoundException's own Javadoc documents (a role from a different
    // Organization is reported identically to "doesn't exist"). A brand-new role can never be
    // part of a pre-existing cycle (nothing references it yet), so no cycle check is needed here
    // — only UpdateWorkspaceRoleService needs one.
    if (command.parentRoleId() != null
        && roles.findById(command.parentRoleId()).stream()
            .noneMatch(role -> role.organizationId().equals(command.organizationId()))) {
      throw new WorkspaceRoleNotFoundException(command.parentRoleId());
    }

    final WorkspaceRole role =
        WorkspaceRole.define(
            command.organizationId(),
            command.name(),
            command.parentRoleId(),
            command.permissions());
    persistOrThrowOnDuplicate(role, command.name());

    auditEvents.write(
        command.actor(),
        "workspace_role.created",
        "WorkspaceRole",
        role.id().toString(),
        "organizationId=" + command.organizationId() + " name=" + command.name());

    outbox.write(
        "WorkspaceRole",
        "workspace_role.created",
        role.id(),
        command.organizationId(),
        WorkspaceRoleCreatedEvent.from(role));

    return role;
  }

  // Extracted (PMD.CyclomaticComplexity — handle() was already at the default threshold):
  // TD-SEC-060,
  // saveAndFlush not save — the pre-check above already confirmed the name is free, but under
  // genuine concurrent creation the loser of that race only finds out once
  // ux_workspace_roles_organization_id_name fires, which must happen synchronously, inside this
  // try, not deferred to the transaction's own commit — same reasoning RegisterAccountService's
  // own identical fix documents for AccountRepository#insert.
  private void persistOrThrowOnDuplicate(final WorkspaceRole role, final String name) {
    try {
      roles.saveAndFlush(role);
    } catch (final DataIntegrityViolationException raceLost) {
      throw new DuplicateWorkspaceRoleNameException(name, raceLost);
    }
  }
}
