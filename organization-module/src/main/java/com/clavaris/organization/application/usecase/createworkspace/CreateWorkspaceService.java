package com.clavaris.organization.application.usecase.createworkspace;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.domain.event.WorkspaceCreatedEvent;
import com.clavaris.organization.domain.model.Workspace;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link CreateWorkspaceUseCase}. {@code organizations} is only ever used for its
 * {@code existsById} check — same "reuse the port that's already there" precedent {@code
 * OrganizationExistsCheckerBridge} already established for a different module entirely.
 */
public class CreateWorkspaceService implements CreateWorkspaceUseCase {

  private final WorkspaceRepository workspaces;
  private final OrganizationRepository organizations;
  private final AuditEventRecorder auditEvents;
  private final EventOutboxWriter outbox;

  public CreateWorkspaceService(
      final WorkspaceRepository workspaces,
      final OrganizationRepository organizations,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter outbox) {
    this.workspaces = workspaces;
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

    auditEvents.write(
        command.actor(),
        "workspace.created",
        "Workspace",
        workspace.id().toString(),
        "organizationId=" + command.organizationId());

    outbox.write(
        "Workspace", "workspace.created", workspace.id(), WorkspaceCreatedEvent.from(workspace));

    return workspace;
  }
}
