package com.clavaris.organization.domain.event;

import com.clavaris.organization.domain.model.WorkspaceRole;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Aggregate type {@code "WorkspaceRole"} — see {@link WorkspaceRoleCreatedEvent}'s own Javadoc. */
public record WorkspaceRoleUpdatedEvent(
    UUID roleId,
    UUID organizationId,
    String name,
    UUID parentRoleId,
    Set<String> permissions,
    Instant occurredAt) {

  public static WorkspaceRoleUpdatedEvent from(final WorkspaceRole role) {
    return new WorkspaceRoleUpdatedEvent(
        role.id(),
        role.organizationId(),
        role.name(),
        role.parentRoleId(),
        role.permissions(),
        Instant.now());
  }
}
