package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/v1/admin/organizations/{organizationId}/workspace-roles}.
 * {@code parentRoleId} is optional (ADR-0027 §3); {@code permissions} defaults to an empty set when
 * omitted — a role with no permissions is valid (e.g. a purely informational team label).
 *
 * <p>{@code @Size(max = 255)} on {@code name}: matches the {@code workspace_roles.name} column and
 * {@code WorkspaceRole}'s own {@code MAX_NAME_LENGTH} — same enforce-it-at-every-layer discipline
 * {@code CreateOrganizationRequest}'s own identical annotation already established (that class's
 * own Javadoc has the full incident this prevents: without it, an over-length name passes Bean
 * Validation entirely and only fails deep in {@code WorkspaceRole}'s own constructor as an
 * unhandled {@code IllegalArgumentException} — a raw 500).
 */
public record CreateWorkspaceRoleRequest(
    @NotBlank @Size(max = 255) String name, UUID parentRoleId, Set<String> permissions) {

  public CreateWorkspaceRoleRequest {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }
}
