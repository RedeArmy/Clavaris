package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import java.util.UUID;

/**
 * HTTP request body for {@code PATCH
 * /api/v1/admin/organizations/{organizationId}/workspace-roles/{roleId}}. Despite the HTTP verb,
 * this is a full-replace request, not a JSON-merge-patch — see {@code UpdateWorkspaceRoleCommand}'s
 * own Javadoc for why.
 */
public record UpdateWorkspaceRoleRequest(
    @NotBlank String name, UUID parentRoleId, Set<String> permissions) {

  public UpdateWorkspaceRoleRequest {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }
}
