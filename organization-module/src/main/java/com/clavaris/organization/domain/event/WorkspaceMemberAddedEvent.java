package com.clavaris.organization.domain.event;

import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate type {@code "WorkspaceMembership"} in this module's own {@code
 * organization_event_outbox} table. {@code accountId} only — never an email/PII in an event payload
 * a future webhook consumer would receive (BR-DATA-01), same discipline as every other event in
 * this codebase. {@code roleId} (ADR-0027 — replaces the old fixed-enum {@code role}) is the
 * assigned {@code WorkspaceRole}'s id, never null on this event (unlike {@code
 * WorkspaceMemberRoleChangedEvent}'s nullable pair): {@code AddWorkspaceMemberCommand#roleId} must
 * always reference a real role.
 */
public record WorkspaceMemberAddedEvent(
    UUID membershipId, UUID workspaceId, UUID accountId, UUID roleId, Instant occurredAt) {

  public static WorkspaceMemberAddedEvent from(final WorkspaceMembership membership) {
    return new WorkspaceMemberAddedEvent(
        membership.id(),
        membership.workspaceId(),
        membership.accountId(),
        membership.roleId(),
        Instant.now());
  }
}
