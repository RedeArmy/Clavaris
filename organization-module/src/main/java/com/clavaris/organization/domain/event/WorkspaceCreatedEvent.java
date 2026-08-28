package com.clavaris.organization.domain.event;

import com.clavaris.organization.domain.model.Workspace;
import java.time.Instant;
import java.util.UUID;

/**
 * Written to this module's own {@code organization_event_outbox} table (same table {@code
 * OrganizationDeletedEvent} already uses — {@code aggregateType} discriminates), aggregate type
 * {@code "Workspace"}. Functionally inert today, same "write-only until a dispatcher exists"
 * posture as every other outbox write in this codebase (webhook-module doesn't exist yet).
 */
public record WorkspaceCreatedEvent(
    UUID workspaceId, UUID organizationId, String name, Instant occurredAt) {

  public static WorkspaceCreatedEvent from(final Workspace workspace) {
    return new WorkspaceCreatedEvent(
        workspace.id(), workspace.organizationId(), workspace.name(), Instant.now());
  }
}
