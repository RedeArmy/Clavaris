package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/v1/admin/organizations}. {@code ownerPlatformAccountId}
 * (ADR-0012) — always required on this REST path: an operator/{@code PlatformClient} caller has no
 * session principal to derive it from the way the dashboard's own controller does, so it must be
 * supplied explicitly, same as {@code name}.
 *
 * <p>{@code @Size(max = 255)} on {@code name}: matches the {@code organizations.name} column
 * (security finding, SDE-III review, 2026-08-22) — without it, an over-length name passed Bean
 * Validation entirely and only failed at the DB as an unhandled {@code
 * DataIntegrityViolationException} (a raw 500).
 */
@SuppressWarnings("PMD.LongVariable")
public record CreateOrganizationRequest(
    @NotBlank @Size(max = 255) String name, @NotNull UUID ownerPlatformAccountId) {}
