package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.WorkspaceMembership;
import java.time.Instant;
import java.util.UUID;

/** {@code roleId} (ADR-0027 — replaces the old fixed-enum {@code role}) may be {@code null}. */
@SuppressWarnings("PMD.ShortVariable")
public record WorkspaceMembershipResponse(
    UUID id, UUID workspaceId, UUID accountId, UUID roleId, Instant createdAt) {

  public static WorkspaceMembershipResponse from(final WorkspaceMembership membership) {
    return new WorkspaceMembershipResponse(
        membership.id(),
        membership.workspaceId(),
        membership.accountId(),
        membership.roleId(),
        membership.createdAt());
  }
}
