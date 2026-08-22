package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Positive;

/**
 * HTTP request body for {@code PUT /api/v1/admin/organizations/{organizationId}/rate-limit-policy}.
 */
public record SetRateLimitPolicyRequest(@Positive int requestsPerMinute) {}
