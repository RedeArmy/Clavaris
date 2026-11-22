package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body for {@code PUT
 * /api/v1/admin/organizations/{organizationId}/social-credentials/{provider}} (ADR-0022). Both
 * fields are the operator's own Google/GitHub OAuth app values, typed in directly — Clavaris never
 * generates either. The limits are the dashboard form's own ({@code SocialConnectionForm}) and the
 * column's ({@code client_id varchar(255)}): without them a body of any size reached the
 * encryption, and a long client id failed at the database as a 500.
 */
public record SetOrganizationSocialCredentialRequest(
    @NotBlank @Size(max = 255) String clientId, @NotBlank @Size(max = 1000) String clientSecret) {}
