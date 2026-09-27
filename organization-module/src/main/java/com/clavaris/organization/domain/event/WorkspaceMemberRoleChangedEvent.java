package com.clavaris.organization.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate type {@code "WorkspaceMembership"} — see {@link WorkspaceMemberAddedEvent}'s Javadoc.
 * {@code previousRoleId}/{@code newRoleId} (ADR-0027 — replaces the old fixed-enum {@code
 * previousRole}/{@code newRole}) are nullable: {@code null} means the membership had, or now has,
 * no {@link com.clavaris.organization.domain.model.WorkspaceRole} assigned (an explicitly allowed
 * "unassigned" state, ADR-0027 §5).
 */
public record WorkspaceMemberRoleChangedEvent(
    UUID membershipId,
    UUID workspaceId,
    UUID accountId,
    UUID previousRoleId,
    UUID newRoleId,
    Instant occurredAt) {

  // "of", matching AccountRegisteredEvent's own "from" static-factory convention family — a short,
  // conventional factory name, not an accidental abbreviation (same precedent
  // RefreshTokenReuseDetectedEvent's own identical suppression already established).
  @SuppressWarnings("PMD.ShortMethodName")
  public static WorkspaceMemberRoleChangedEvent of(
      final UUID membershipId,
      final UUID workspaceId,
      final UUID accountId,
      final UUID previousRoleId,
      final UUID newRoleId) {
    return new WorkspaceMemberRoleChangedEvent(
        membershipId, workspaceId, accountId, previousRoleId, newRoleId, Instant.now());
  }
}
