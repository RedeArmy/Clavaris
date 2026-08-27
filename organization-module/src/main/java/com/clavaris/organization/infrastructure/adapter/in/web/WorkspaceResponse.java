package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.Workspace;
import java.time.Instant;
import java.util.UUID;

@SuppressWarnings("PMD.ShortVariable")
public record WorkspaceResponse(UUID id, UUID organizationId, String name, Instant createdAt) {

  public static WorkspaceResponse from(final Workspace workspace) {
    return new WorkspaceResponse(
        workspace.id(), workspace.organizationId(), workspace.name(), workspace.createdAt());
  }
}
