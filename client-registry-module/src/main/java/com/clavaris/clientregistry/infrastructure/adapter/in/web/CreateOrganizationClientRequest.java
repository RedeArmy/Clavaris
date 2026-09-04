package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * HTTP request body for {@code POST /api/v1/admin/organizations/{organizationId}/secret-keys}
 * (ADR-0023). {@code allowedScopes} is caller-supplied — same free-text-list-validated-by-the-
 * use-case-layer convention {@code SetSocialLoginPolicyRequest}'s own Javadoc already establishes
 * for a similar shape, letting an operator mint a narrowly-scoped Secret Key rather than always
 * granting every reachable capability.
 */
public record CreateOrganizationClientRequest(@NotEmpty List<String> allowedScopes) {}
