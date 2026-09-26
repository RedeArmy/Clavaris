package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/v1/admin/organizations/{organizationId}/workspace-roles}.
 * {@code parentRoleId} is optional (ADR-0027 §3); {@code permissions} defaults to an empty set when
 * omitted — a role with no permissions is valid (e.g. a purely informational team label).
 */
public record CreateWorkspaceRoleRequest(
    @NotBlank String name, UUID parentRoleId, Set<String> permissions) {

  public CreateWorkspaceRoleRequest {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }
}
