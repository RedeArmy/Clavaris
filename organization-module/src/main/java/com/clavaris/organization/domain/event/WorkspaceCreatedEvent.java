package com.clavaris.organization.domain.event;

import com.clavaris.organization.domain.model.Workspace;
import java.time.Instant;
import java.util.UUID;

/**
 * Written to this module's own {@code organization_event_outbox} table (same table {@code
 * OrganizationDeletedEvent} already uses — {@code aggregateType} discriminates), aggregate type
 * {@code "Workspace"}. {@code webhook-module} (ADR-0007) shipped 2026-09-02 and drains this write
 * for real now — no longer the write-only-with-no-consumer state this comment used to describe.
 */
public record WorkspaceCreatedEvent(
    UUID workspaceId, UUID organizationId, String name, Instant occurredAt) {

  public static WorkspaceCreatedEvent from(final Workspace workspace) {
    return new WorkspaceCreatedEvent(
        workspace.id(), workspace.organizationId(), workspace.name(), Instant.now());
  }
}
