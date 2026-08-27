package com.clavaris.organization.domain.event;

import com.clavaris.organization.domain.model.WorkspaceRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate type {@code "WorkspaceMembership"} — see {@link WorkspaceMemberAddedEvent}'s Javadoc.
 */
public record WorkspaceMemberRoleChangedEvent(
    UUID membershipId,
    UUID workspaceId,
    UUID accountId,
    WorkspaceRole previousRole,
    WorkspaceRole newRole,
    Instant occurredAt) {

  // "of", matching AccountRegisteredEvent's own "from" static-factory convention family — a short,
  // conventional factory name, not an accidental abbreviation (same precedent
  // RefreshTokenReuseDetectedEvent's own identical suppression already established).
  @SuppressWarnings("PMD.ShortMethodName")
  public static WorkspaceMemberRoleChangedEvent of(
      final UUID membershipId,
      final UUID workspaceId,
      final UUID accountId,
      final WorkspaceRole previousRole,
      final WorkspaceRole newRole) {
    return new WorkspaceMemberRoleChangedEvent(
        membershipId, workspaceId, accountId, previousRole, newRole, Instant.now());
  }
}
