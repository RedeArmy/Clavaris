package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body for {@code POST
 * /api/v1/admin/organizations/{organizationId}/access-restrictions/check} (TD-FUT-069). The length
 * matches the column an entry is stored in, so nothing longer can be on a list.
 */
public record AccessRestrictionCheckRequest(@NotBlank @Size(max = 320) String email) {}
