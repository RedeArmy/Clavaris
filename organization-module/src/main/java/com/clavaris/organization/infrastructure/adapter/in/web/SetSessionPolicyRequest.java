package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * HTTP request body for {@code PUT /api/v1/admin/organizations/{organizationId}/session-policy}.
 * The {@code @Min}/{@code @Max} bounds mirror {@code SessionPolicy}'s own range constants exactly —
 * duplicated here deliberately (same precedent every other Bean-Validation-backed form in this
 * codebase follows) so a bad value never even reaches the service layer.
 */
@SuppressWarnings("PMD.LongVariable")
public record SetSessionPolicyRequest(
    @Min(5) @Max(5_256_000) int maximumLifetimeMinutes,
    @Min(5) @Max(525_600) int inactivityTimeoutMinutes,
    @Min(1) @Max(10) int reverificationWindowMinutes,
    boolean multiSessionHandlingEnabled) {}
