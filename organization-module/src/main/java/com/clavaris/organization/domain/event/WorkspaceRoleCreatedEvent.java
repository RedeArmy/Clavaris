package com.clavaris.organization.domain.event;

import com.clavaris.organization.domain.model.WorkspaceRole;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * ADR-0027 §4: aggregate type {@code "WorkspaceRole"} in this module's own {@code
 * organization_event_outbox} table — see {@link WorkspaceCreatedEvent}'s own Javadoc for the shared
 * table/dispatcher shape. {@code name}/{@code permissions} are opaque, consumer-defined strings
 * (ADR-0027 §1) — carried verbatim, never interpreted here either.
 */
public record WorkspaceRoleCreatedEvent(
    UUID roleId,
    UUID organizationId,
    String name,
    UUID parentRoleId,
    Set<String> permissions,
    Instant occurredAt) {

  public static WorkspaceRoleCreatedEvent from(final WorkspaceRole role) {
    return new WorkspaceRoleCreatedEvent(
        role.id(),
        role.organizationId(),
        role.name(),
        role.parentRoleId(),
        role.permissions(),
        Instant.now());
  }
}
