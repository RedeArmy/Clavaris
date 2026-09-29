package com.clavaris.organization.domain.event;

import com.clavaris.organization.domain.model.Workspace;
import java.time.Instant;
import java.util.UUID;

/**
 * Written to this module's own {@code organization_event_outbox} table, aggregate type {@code
 * "Workspace"} — same table/discriminator convention as {@link WorkspaceCreatedEvent}. {@code name}
 * is captured here, not re-read after the fact: by the time this event is built, {@code
 * DeleteWorkspaceService} is about to hard-delete the {@code Workspace} row, same "last point this
 * field is still available" reasoning {@code OrganizationDeletedEvent}'s own {@code name} already
 * documents.
 */
public record WorkspaceDeletedEvent(
    UUID workspaceId, UUID organizationId, String name, Instant occurredAt) {

  public static WorkspaceDeletedEvent from(final Workspace workspace) {
    return new WorkspaceDeletedEvent(
        workspace.id(), workspace.organizationId(), workspace.name(), Instant.now());
  }
}
