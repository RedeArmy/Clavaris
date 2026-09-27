package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.WorkspaceRole;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("PMD.ShortVariable")
public record WorkspaceRoleResponse(
    UUID id,
    UUID organizationId,
    String name,
    UUID parentRoleId,
    Set<String> permissions,
    boolean reserved,
    Instant createdAt) {

  public static WorkspaceRoleResponse from(final WorkspaceRole role) {
    return new WorkspaceRoleResponse(
        role.id(),
        role.organizationId(),
        role.name(),
        role.parentRoleId(),
        role.permissions(),
        role.reserved(),
        role.createdAt());
  }
}
