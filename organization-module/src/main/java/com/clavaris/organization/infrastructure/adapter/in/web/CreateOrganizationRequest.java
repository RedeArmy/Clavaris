package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/v1/admin/organizations}. {@code ownerPlatformAccountId}
 * (ADR-0012) — always required on this REST path: an operator/{@code PlatformClient} caller has no
 * session principal to derive it from the way the dashboard's own controller does, so it must be
 * supplied explicitly, same as {@code name}.
 */
@SuppressWarnings("PMD.LongVariable")
public record CreateOrganizationRequest(
    @NotBlank String name, @NotNull UUID ownerPlatformAccountId) {}
