package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP request body for {@code PUT
 * /api/v1/admin/organizations/{organizationId}/social-credentials/{provider}} (ADR-0022). Both
 * fields are the operator's own Google/GitHub OAuth app values, typed in directly — Clavaris never
 * generates either.
 */
public record SetOrganizationSocialCredentialRequest(
    @NotBlank String clientId, @NotBlank String clientSecret) {}
