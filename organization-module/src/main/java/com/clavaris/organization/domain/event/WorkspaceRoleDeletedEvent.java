package com.clavaris.organization.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Aggregate type {@code "WorkspaceRole"} — see {@link WorkspaceRoleCreatedEvent}'s own Javadoc. */
public record WorkspaceRoleDeletedEvent(UUID roleId, UUID organizationId, Instant occurredAt) {

  // "of", matching AccountRegisteredEvent's own "from" static-factory convention family — a
  // short, conventional factory name, not an accidental abbreviation (same precedent
  // WorkspaceMemberRoleChangedEvent's own identical suppression already established).
  @SuppressWarnings("PMD.ShortMethodName")
  public static WorkspaceRoleDeletedEvent of(final UUID roleId, final UUID organizationId) {
    return new WorkspaceRoleDeletedEvent(roleId, organizationId, Instant.now());
  }
}
