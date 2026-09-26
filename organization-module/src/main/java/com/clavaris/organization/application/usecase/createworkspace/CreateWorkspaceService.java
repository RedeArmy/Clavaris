package com.clavaris.organization.application.usecase.createworkspace;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceCreatedEvent;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link CreateWorkspaceUseCase}. {@code organizations} is only ever used for its
 * {@code existsById} check — same "reuse the port that's already there" precedent {@code
 * OrganizationExistsCheckerBridge} already established for a different module entirely.
 *
 * <p>ADR-0027 §2: also ensures this Organization has its one reserved bootstrap {@link
 * WorkspaceRole} — idempotent, since {@code WorkspaceRole} is Organization-scoped (shared across
 * every Workspace an Organization owns), not created fresh for every Workspace. Not assigned to
 * anyone here: this class has no "creating member" to assign it to (a Workspace is created empty;
 * BR-WS-04's own provisioning happens later, per member, via {@code AddWorkspaceMemberService}) —
 * it only needs to exist so the member-add flow has a role to offer.
 */
public class CreateWorkspaceService implements CreateWorkspaceUseCase {

  // ADR-0027 §2: the reserved role's display name — consumer-renamable later (Slice 3, not yet
  // shipped), same "opaque, consumer-owned label" posture WorkspaceRole.name()'s own Javadoc
  // documents; this is only ever a starting default.
  // PMD.LongVariable: RESERVED_ROLE_NAME names exactly what it is — same convention this class's
  // own EMAIL-style constants in sibling use cases already follow.
  @SuppressWarnings("PMD.LongVariable")
  private static final String RESERVED_ROLE_NAME = "Admin";

  private final WorkspaceRepository workspaces;
  private final WorkspaceRoleRepository roles;
  private final OrganizationRepository organizations;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  @SuppressWarnings("java:S107") // one parameter per collaborating port — same rationale as
  // AddWorkspaceMemberService's own identical suppression.
  public CreateWorkspaceService(
      final WorkspaceRepository workspaces,
      final WorkspaceRoleRepository roles,
      final OrganizationRepository organizations,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.workspaces = workspaces;
    this.roles = roles;
    this.organizations = organizations;
    this.auditEvents = auditEvents;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public Workspace handle(final CreateWorkspaceCommand command) {
    if (!organizations.existsById(command.organizationId())) {
      throw new OrganizationNotFoundException(command.organizationId());
    }

    final Workspace workspace = Workspace.register(command.organizationId(), command.name());
    workspaces.save(workspace);
    ensureReservedRoleExists(command.organizationId());

    auditEvents.write(
        command.actor(),
        "workspace.created",
        "Workspace",
        workspace.id().toString(),
        "organizationId=" + command.organizationId());

    outbox.write(
        "Workspace",
        "workspace.created",
        workspace.id(),
        workspace.organizationId(),
        WorkspaceCreatedEvent.from(workspace));

    return workspace;
  }

  private void ensureReservedRoleExists(final UUID organizationId) {
    final boolean alreadyExists =
        roles.findAllByOrganizationId(organizationId).stream().anyMatch(WorkspaceRole::reserved);
    if (!alreadyExists) {
      roles.save(WorkspaceRole.defineReserved(organizationId, RESERVED_ROLE_NAME));
    }
  }
}
