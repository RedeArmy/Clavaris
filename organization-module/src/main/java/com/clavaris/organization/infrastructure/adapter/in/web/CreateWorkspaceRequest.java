package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body for {@code POST /api/v1/admin/organizations/{organizationId}/workspaces}.
 * {@code @Size(max = 255)} matches the {@code workspaces.name} column — same enforce-it-at-every-
 * layer discipline {@code CreateOrganizationRequest} already established for {@code organizations}.
 */
public record CreateWorkspaceRequest(@NotBlank @Size(max = 255) String name) {}
