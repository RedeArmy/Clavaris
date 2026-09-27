package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

/**
 * HTTP request body for {@code PATCH
 * /api/v1/admin/organizations/{organizationId}/workspace-roles/{roleId}}. Despite the HTTP verb,
 * this is a full-replace request, not a JSON-merge-patch — see {@code UpdateWorkspaceRoleCommand}'s
 * own Javadoc for why.
 *
 * <p>{@code @Size(max = 255)} on {@code name}: same enforce-it-at-every-layer discipline as {@code
 * CreateWorkspaceRoleRequest}'s own identical annotation — see that class's own Javadoc.
 */
public record UpdateWorkspaceRoleRequest(
    @NotBlank @Size(max = 255) String name, UUID parentRoleId, Set<String> permissions) {

  public UpdateWorkspaceRoleRequest {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }
}
