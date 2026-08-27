package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.time.Instant;
import java.util.UUID;

@SuppressWarnings("PMD.ShortVariable")
public record WorkspaceMembershipResponse(
    UUID id, UUID workspaceId, UUID accountId, WorkspaceRole role, Instant createdAt) {

  public static WorkspaceMembershipResponse from(final WorkspaceMembership membership) {
    return new WorkspaceMembershipResponse(
        membership.id(),
        membership.workspaceId(),
        membership.accountId(),
        membership.role(),
        membership.createdAt());
  }
}
