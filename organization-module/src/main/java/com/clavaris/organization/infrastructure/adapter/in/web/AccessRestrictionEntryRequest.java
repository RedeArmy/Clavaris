package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.RestrictionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * HTTP request body for {@code POST
 * /api/v1/admin/organizations/{organizationId}/access-restrictions}. {@code identifier} is either a
 * full email ({@code "user@example.com"}) or a domain pattern ({@code "@example.com"}) — see {@code
 * AccessRestrictionEntry}'s own Javadoc.
 */
public record AccessRestrictionEntryRequest(
    @NotNull RestrictionType type, @NotBlank String identifier) {}
