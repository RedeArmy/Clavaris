package com.clavaris.organization.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate type {@code "WorkspaceMembership"} — see {@link WorkspaceMemberAddedEvent}'s Javadoc.
 * Captured as plain ids, not a domain object: by the time this is raised, the {@code
 * WorkspaceMembership} row this describes has already been deleted (same "last point this field is
 * still available" reasoning as {@code AccountDeletedEvent}'s own {@code email}).
 */
public record WorkspaceMemberRemovedEvent(
    UUID membershipId, UUID workspaceId, UUID accountId, Instant occurredAt) {

  // "of", matching AccountRegisteredEvent's own "from" static-factory convention family — a short,
  // conventional factory name, not an accidental abbreviation (same precedent
  // RefreshTokenReuseDetectedEvent's own identical suppression already established).
  @SuppressWarnings("PMD.ShortMethodName")
  public static WorkspaceMemberRemovedEvent of(
      final UUID membershipId, final UUID workspaceId, final UUID accountId) {
    return new WorkspaceMemberRemovedEvent(membershipId, workspaceId, accountId, Instant.now());
  }
}
