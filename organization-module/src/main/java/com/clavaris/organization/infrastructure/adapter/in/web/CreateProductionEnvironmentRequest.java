package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body for {@code POST
 * /api/v1/admin/organizations/{id}:create-production-environment}. {@code name} is required and not
 * defaulted from the source Organization's own name — see {@code
 * CreateProductionEnvironmentCommand}'s own Javadoc for why. Same {@code @Size(max = 255)} as
 * {@code CreateOrganizationRequest}, matching the same {@code organizations.name} column.
 */
public record CreateProductionEnvironmentRequest(@NotBlank @Size(max = 255) String name) {}
