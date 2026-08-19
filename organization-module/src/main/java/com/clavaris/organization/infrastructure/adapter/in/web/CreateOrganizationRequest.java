package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/** HTTP request body for {@code POST /api/v1/admin/organizations}. */
public record CreateOrganizationRequest(@NotBlank String name) {}
